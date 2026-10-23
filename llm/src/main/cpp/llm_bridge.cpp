// JNI bridge to llama.cpp: load a GGUF model, generate a completion for one prompt, cancel, free.
// One context per model; calls on a handle must not overlap (the Kotlin side serialises them),
// except nativeCancel, which may come from any thread.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "llmbridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::atomic<bool> cancelled{false};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

bool abort_callback(void *data) {
    return static_cast<Session *>(data)->cancelled.load(std::memory_order_relaxed);
}

void throw_java(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    // parse_special: the prompt contains the chat template's control tokens as text.
    int32_t n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> tokens(std::max(n, 0));
    if (n > 0 && llama_tokenize(vocab, text.data(), (int32_t) text.size(), tokens.data(), n, false, true) < 0) {
        tokens.clear();
    }
    return tokens;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_smartglasses_llm_LlmNative_nativeInit(JNIEnv *, jclass) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_smartglasses_llm_LlmNative_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint threads, jint context_size) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU only
    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) {
        throw_java(env, "The AI translation model could not be loaded.");
        return 0;
    }
    auto *session = new Session();
    session->model = model;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) context_size;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    cparams.abort_callback = abort_callback;
    cparams.abort_callback_data = session;
    session->ctx = llama_init_from_model(model, cparams);
    if (session->ctx == nullptr) {
        llama_model_free(model);
        delete session;
        throw_java(env, "Not enough memory for the AI translation model.");
        return 0;
    }
    char desc[128];
    llama_model_desc(model, desc, sizeof(desc));
    LOGI("model loaded: %s, ctx=%d, threads=%d", desc, context_size, threads);
    return reinterpret_cast<jlong>(session);
}

/**
 * Generates up to max_tokens after the prompt and returns the text as UTF-8 bytes (decoded in
 * Kotlin: tokens may split multi-byte characters). Returns null when cancelled.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_smartglasses_llm_LlmNative_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jbyteArray jprompt, jint max_tokens,
        jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty, jint seed) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) {
        throw_java(env, "Model not loaded.");
        return nullptr;
    }
    session->cancelled.store(false);

    std::string prompt;
    {
        jsize len = env->GetArrayLength(jprompt);
        prompt.resize(len);
        env->GetByteArrayRegion(jprompt, 0, len, reinterpret_cast<jbyte *>(prompt.data()));
    }

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    llama_memory_clear(llama_get_memory(session->ctx), true);

    std::vector<llama_token> tokens = tokenize(vocab, prompt);
    const int n_ctx = (int) llama_n_ctx(session->ctx);
    if (tokens.empty() || (int) tokens.size() + max_tokens > n_ctx) {
        throw_java(env, "The text is too long for the AI translation model.");
        return nullptr;
    }

    // Prompt in batches.
    const int n_batch = (int) llama_n_batch(session->ctx);
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        int n = std::min<int>(n_batch, (int) (tokens.size() - i));
        int rc = llama_decode(session->ctx, llama_batch_get_one(tokens.data() + i, n));
        if (rc == 2 || session->cancelled.load()) return nullptr;  // aborted
        if (rc != 0) {
            throw_java(env, "AI translation failed while reading the text.");
            return nullptr;
        }
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t) seed));

    std::string output;
    bool failed = false;
    char piece[256];
    for (int i = 0; i < max_tokens; ++i) {
        if (session->cancelled.load()) break;
        llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        // End of the answer, or any control token (the chat template's markers).
        if (llama_vocab_is_eog(vocab, token) || llama_vocab_is_control(vocab, token)) break;
        int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) output.append(piece, n);
        int rc = llama_decode(session->ctx, llama_batch_get_one(&token, 1));
        if (rc == 2) break;  // aborted
        if (rc != 0) {
            failed = true;
            break;
        }
    }
    llama_sampler_free(sampler);

    if (session->cancelled.load()) return nullptr;
    if (failed) {
        throw_java(env, "AI translation failed while writing the result.");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray((jsize) output.size());
    env->SetByteArrayRegion(result, 0, (jsize) output.size(), reinterpret_cast<const jbyte *>(output.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_com_smartglasses_llm_LlmNative_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session != nullptr) session->cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_com_smartglasses_llm_LlmNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr) llama_free(session->ctx);
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}

}  // extern "C"
