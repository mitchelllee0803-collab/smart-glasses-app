// JNI bridge between Kotlin (com.offlinetranslate.app.engine.Native) and the C++ libraries.
// Only inference lives here; all policy (routing, sentence splitting, post-processing,
// script handling) is in the unit-tested Kotlin core (com.offlinetranslate.engine).
#include <android/log.h>
#include <jni.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "SimpleConverter.hpp"
#include "ctranslate2/translator.h"
#include "sentencepiece_processor.h"

#define LOG_TAG "otengine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

bool g_initialised = false;
jclass g_string_class = nullptr;
jclass g_string_array_class = nullptr;


void throw_java(JNIEnv* env, const char* message) {
  jclass cls = env->FindClass("java/lang/RuntimeException");
  env->ThrowNew(cls, message);
}

template <typename F>
auto guarded(JNIEnv* env, F&& fn, decltype(fn()) fallback) -> decltype(fn()) {
  try {
    return fn();
  } catch (const std::exception& e) {
    throw_java(env, e.what());
  } catch (...) {
    throw_java(env, "Unknown native error");
  }
  return fallback;
}

std::string to_string(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string out(c);
  env->ReleaseStringUTFChars(s, c);
  return out;
}

// Modified-UTF-8 (JNI) cannot carry supplementary characters; build Java strings from UTF-16.
jstring to_jstring(JNIEnv* env, const std::string& utf8) {
  std::u16string u16;
  u16.reserve(utf8.size());
  for (size_t i = 0; i < utf8.size();) {
    unsigned char c = utf8[i];
    char32_t cp;
    int len;
    if (c < 0x80) { cp = c; len = 1; }
    else if ((c >> 5) == 0x6) { cp = c & 0x1f; len = 2; }
    else if ((c >> 4) == 0xe) { cp = c & 0x0f; len = 3; }
    else if ((c >> 3) == 0x1e) { cp = c & 0x07; len = 4; }
    else { cp = 0xfffd; len = 1; }
    if (i + len > utf8.size()) { cp = 0xfffd; len = 1; }
    for (int k = 1; k < len; ++k) cp = (cp << 6) | (utf8[i + k] & 0x3f);
    i += len;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      u16.push_back(static_cast<char16_t>(0xd800 + (cp >> 10)));
      u16.push_back(static_cast<char16_t>(0xdc00 + (cp & 0x3ff)));
    } else {
      u16.push_back(static_cast<char16_t>(cp));
    }
  }
  return env->NewString(reinterpret_cast<const jchar*>(u16.data()), static_cast<jsize>(u16.size()));
}

// Java String -> UTF-8 via UTF-16 (handles supplementary characters correctly).
std::string to_utf8(JNIEnv* env, jstring s) {
  if (!s) return {};
  const jsize n = env->GetStringLength(s);
  const jchar* chars = env->GetStringChars(s, nullptr);
  std::string out;
  out.reserve(n * 3);
  for (jsize i = 0; i < n; ++i) {
    char32_t cp = chars[i];
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < n && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
      cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
    }
    if (cp < 0x80) out += static_cast<char>(cp);
    else if (cp < 0x800) { out += static_cast<char>(0xc0 | (cp >> 6)); out += static_cast<char>(0x80 | (cp & 0x3f)); }
    else if (cp < 0x10000) {
      out += static_cast<char>(0xe0 | (cp >> 12)); out += static_cast<char>(0x80 | ((cp >> 6) & 0x3f));
      out += static_cast<char>(0x80 | (cp & 0x3f));
    } else {
      out += static_cast<char>(0xf0 | (cp >> 18)); out += static_cast<char>(0x80 | ((cp >> 12) & 0x3f));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3f)); out += static_cast<char>(0x80 | (cp & 0x3f));
    }
  }
  env->ReleaseStringChars(s, chars);
  return out;
}

std::vector<std::string> to_vector(JNIEnv* env, jobjectArray arr) {
  std::vector<std::string> out;
  const jsize n = env->GetArrayLength(arr);
  out.reserve(n);
  for (jsize i = 0; i < n; ++i) {
    auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
    out.push_back(to_utf8(env, s));
    env->DeleteLocalRef(s);
  }
  return out;
}

jobjectArray to_jarray(JNIEnv* env, const std::vector<std::string>& v) {
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(v.size()), g_string_class, nullptr);
  for (size_t i = 0; i < v.size(); ++i) {
    jstring s = to_jstring(env, v[i]);
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
    env->DeleteLocalRef(s);
  }
  return arr;
}

void require_init() {
  if (!g_initialised) throw std::runtime_error("Native engine not initialised");
}

ctranslate2::ReplicaPoolConfig pool_config(jint threads) {
  ctranslate2::ReplicaPoolConfig cfg;
  cfg.num_threads_per_replica = static_cast<size_t>(threads > 0 ? threads : 4);
  return cfg;
}

// Models are imported into app storage (Translate-models), so CTranslate2 reads plain files.
ctranslate2::models::ModelLoader file_loader(const std::string& dir) {
  ctranslate2::models::ModelLoader loader(dir);
  loader.device = ctranslate2::Device::CPU;
  loader.compute_type = ctranslate2::ComputeType::INT8;
  return loader;
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_offlinetranslate_app_engine_Native_##name

// ------------------------------------------------------------------- setup
JNI_FN(void, nativeInit)(JNIEnv* env, jclass) {
  if (!g_string_class) {
    g_string_class = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/String")));
    g_string_array_class = static_cast<jclass>(env->NewGlobalRef(env->FindClass("[Ljava/lang/String;")));
  }
  g_initialised = true;
  LOGI("native engine initialised");
}

// -------------------------------------------------------------- translation
JNI_FN(jlong, translatorCreate)(JNIEnv* env, jclass, jstring dir, jint threads) {
  return guarded(env, [&]() -> jlong {
    require_init();
    auto* t = new ctranslate2::Translator(file_loader(to_string(env, dir)), pool_config(threads));
    return reinterpret_cast<jlong>(t);
  }, 0);
}

JNI_FN(void, translatorFree)(JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<ctranslate2::Translator*>(handle);
}

JNI_FN(jobjectArray, translatorTranslate)(JNIEnv* env, jclass, jlong handle, jobjectArray batch, jint beam,
                                         jfloat length_penalty, jint max_decoding) {
  return guarded(env, [&]() -> jobjectArray {
    auto* t = reinterpret_cast<ctranslate2::Translator*>(handle);
    std::vector<std::vector<std::string>> source;
    const jsize n = env->GetArrayLength(batch);
    for (jsize i = 0; i < n; ++i) {
      auto row = static_cast<jobjectArray>(env->GetObjectArrayElement(batch, i));
      source.push_back(to_vector(env, row));
      env->DeleteLocalRef(row);
    }
    // Same decoding parameters as Argos Translate / the Windows worker.
    ctranslate2::TranslationOptions opts;
    opts.beam_size = static_cast<size_t>(beam);
    opts.length_penalty = length_penalty;
    opts.max_decoding_length = static_cast<size_t>(max_decoding);
    opts.replace_unknowns = true;
    opts.num_hypotheses = 1;
    const auto results = t->translate_batch(source, opts, 32, ctranslate2::BatchType::Tokens);
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(results.size()), g_string_array_class, nullptr);
    for (size_t i = 0; i < results.size(); ++i) {
      jobjectArray row = to_jarray(env, results[i].output());
      env->SetObjectArrayElement(out, static_cast<jsize>(i), row);
      env->DeleteLocalRef(row);
    }
    return out;
  }, nullptr);
}

// ------------------------------------------------------------ SentencePiece
JNI_FN(jlong, spCreate)(JNIEnv* env, jclass, jstring model_path) {
  return guarded(env, [&]() -> jlong {
    require_init();
    const std::string path = to_string(env, model_path);
    auto sp = std::make_unique<sentencepiece::SentencePieceProcessor>();
    const auto status = sp->Load(path);
    if (!status.ok()) throw std::runtime_error("Cannot load tokenizer " + path + ": " + status.ToString());
    return reinterpret_cast<jlong>(sp.release());
  }, 0);
}

JNI_FN(void, spFree)(JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}

JNI_FN(jobjectArray, spEncode)(JNIEnv* env, jclass, jlong handle, jstring text) {
  return guarded(env, [&]() -> jobjectArray {
    auto* sp = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
    std::vector<std::string> pieces;
    const auto status = sp->Encode(to_utf8(env, text), &pieces);
    if (!status.ok()) throw std::runtime_error(status.ToString());
    return to_jarray(env, pieces);
  }, nullptr);
}

JNI_FN(jstring, spDecode)(JNIEnv* env, jclass, jlong handle, jobjectArray pieces) {
  return guarded(env, [&]() -> jstring {
    auto* sp = reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
    std::string out;
    const auto status = sp->Decode(to_vector(env, pieces), &out);
    if (!status.ok()) throw std::runtime_error(status.ToString());
    return to_jstring(env, out);
  }, nullptr);
}

// ------------------------------------------------------------------- OpenCC
JNI_FN(jlong, openccCreate)(JNIEnv* env, jclass, jstring config_path) {
  return guarded(env, [&]() -> jlong {
    return reinterpret_cast<jlong>(new opencc::SimpleConverter(to_string(env, config_path)));
  }, 0);
}

JNI_FN(void, openccFree)(JNIEnv*, jclass, jlong handle) { delete reinterpret_cast<opencc::SimpleConverter*>(handle); }

JNI_FN(jstring, openccConvert)(JNIEnv* env, jclass, jlong handle, jstring text) {
  return guarded(env, [&]() -> jstring {
    auto* c = reinterpret_cast<opencc::SimpleConverter*>(handle);
    return to_jstring(env, c->Convert(to_utf8(env, text)));
  }, nullptr);
}
