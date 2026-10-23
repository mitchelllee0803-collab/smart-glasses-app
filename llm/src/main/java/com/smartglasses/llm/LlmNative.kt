package com.smartglasses.llm

/** JNI entry points of libllmbridge.so (src/main/cpp/llm_bridge.cpp). */
internal object LlmNative {
    @JvmStatic external fun nativeInit()

    /** Returns a handle; throws IllegalStateException when the model or its context cannot be created. */
    @JvmStatic external fun nativeLoad(path: String, threads: Int, contextSize: Int): Long

    /** UTF-8 bytes of the completion, or null when cancelled. */
    @JvmStatic external fun nativeGenerate(
        handle: Long,
        prompt: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        seed: Int,
    ): ByteArray?

    @JvmStatic external fun nativeCancel(handle: Long)

    @JvmStatic external fun nativeFree(handle: Long)
}
