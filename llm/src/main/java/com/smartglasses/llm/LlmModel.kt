package com.smartglasses.llm

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class SamplingParams(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    /** Fixed seed: the same sentence gives the same result. */
    val seed: Int = 42,
)

class LlmCancelledException : RuntimeException("Generation cancelled.")

/**
 * A GGUF model loaded with llama.cpp (CPU). Not thread-safe except [cancel]: callers run [generate]
 * and [close] on one thread.
 */
class LlmModel private constructor(private var handle: Long) : AutoCloseable {

    /** Text after [prompt] (special tokens of the chat template are parsed). Blocking. */
    fun generate(prompt: String, maxTokens: Int, sampling: SamplingParams): String {
        check(handle != 0L) { "Model closed." }
        val bytes = LlmNative.nativeGenerate(
            handle, prompt.toByteArray(Charsets.UTF_8), maxTokens,
            sampling.temperature, sampling.topP, sampling.topK, sampling.repeatPenalty, sampling.seed,
        ) ?: throw LlmCancelledException()
        return String(bytes, Charsets.UTF_8)
    }

    /** Stops a running [generate] from any thread; it then throws [LlmCancelledException]. */
    fun cancel() {
        val h = handle
        if (h != 0L) LlmNative.nativeCancel(h)
    }

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) LlmNative.nativeFree(h)
    }

    companion object {
        private val initialised = AtomicBoolean(false)

        /**
         * Whether this phone can run the library: it is built for ARMv8.2 dot-product and FP16
         * instructions, and executing them on an older CPU would crash the app.
         */
        fun isCpuSupported(): Boolean = CpuFeatures.read().let { "asimddp" in it && "asimdhp" in it }

        /** Loads the native library and the model. Throws IllegalStateException on failure. */
        fun load(file: File, threads: Int, contextSize: Int): LlmModel {
            check(isCpuSupported()) { "This phone's processor is not supported by the AI translation model." }
            if (initialised.compareAndSet(false, true)) {
                System.loadLibrary("llmbridge")
                LlmNative.nativeInit()
            }
            return LlmModel(LlmNative.nativeLoad(file.absolutePath, threads, contextSize))
        }
    }
}

internal object CpuFeatures {
    /** The "Features" flags of /proc/cpuinfo (all cores report the same ISA on Android phones). */
    fun read(): Set<String> = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.filter { it.startsWith("Features") }
                .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }
                .toSet()
        }
    }.getOrDefault(emptySet())
}
