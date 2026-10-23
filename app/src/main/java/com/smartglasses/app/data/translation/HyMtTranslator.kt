package com.smartglasses.app.data.translation

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import com.smartglasses.app.R
import com.smartglasses.app.di.ApplicationScope
import com.smartglasses.app.domain.AiTranslator
import com.smartglasses.app.domain.model.Language
import com.smartglasses.llm.LlmCancelledException
import com.smartglasses.llm.LlmModel
import com.smartglasses.llm.SamplingParams
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

sealed interface AiModelState {
    /** This phone cannot run the model; the standard translator is used. */
    data class Unsupported(val reason: Reason) : AiModelState {
        enum class Reason { PROCESSOR, MEMORY }
    }
    data class NotInstalled(val importError: String? = null) : AiModelState
    data class Importing(val copiedBytes: Long, val totalBytes: Long) : AiModelState
    data class Installed(val sizeBytes: Long) : AiModelState
}

/**
 * Tencent Hy-MT2 1.8B (GGUF, run by llama.cpp on the CPU) as the translator of finished sentences.
 * The model file is imported once from the phone's storage; nothing is downloaded. It is loaded on
 * first use and released after [IDLE_UNLOAD_MS] without use. All model work runs on one thread.
 */
@Singleton
class HyMtTranslator @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) : AiTranslator {
    private val modelDir = File(context.noBackupFilesDir, "ai-translation")
    private val modelFile = File(modelDir, MODEL_FILE_NAME)
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "hy-mt") }
    private val importCancelled = AtomicBoolean(false)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AiModelState> = _state.asStateFlow()

    // Only touched on [executor].
    private var model: LlmModel? = null
    /** The model while a generation runs, so cancellation can reach it from another thread. */
    @Volatile private var runningModel: LlmModel? = null
    @Volatile private var lastUsed = 0L

    init {
        executor.scheduleWithFixedDelay({
            if (model != null && SystemClock.elapsedRealtime() - lastUsed > IDLE_UNLOAD_MS) {
                model?.close()
                model = null
                Log.i(TAG, "unloaded idle AI translation model")
            }
        }, 1, 1, TimeUnit.MINUTES)
    }

    private fun initialState(): AiModelState = when {
        !LlmModel.isCpuSupported() -> AiModelState.Unsupported(AiModelState.Unsupported.Reason.PROCESSOR)
        totalMemory() < MIN_TOTAL_MEMORY -> AiModelState.Unsupported(AiModelState.Unsupported.Reason.MEMORY)
        modelFile.isFile -> AiModelState.Installed(modelFile.length())
        else -> AiModelState.NotInstalled()
    }

    private fun totalMemory(): Long =
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }.totalMem

    override fun handles(source: Language, target: Language): Boolean =
        _state.value is AiModelState.Installed && source != target &&
            // Simplified <-> Traditional Chinese is a script conversion: the exact OpenCC tables do it.
            !(source.isChinese && target.isChinese)

    override suspend fun translate(text: String, source: Language, target: Language, context: List<String>): String {
        val prompt = HyMtPrompt.build(text, source, target, if (USE_CONTEXT) context else emptyList())
        val maxTokens = HyMtPrompt.maxNewTokens(text)
        val output = withTimeout(TRANSLATE_TIMEOUT_MS) { generate(prompt, maxTokens) }
        return HyMtPrompt.clean(output).ifEmpty { throw IOException("The AI translation was empty.") }
    }

    private suspend fun generate(prompt: String, maxTokens: Int): String = suspendCancellableCoroutine { cont ->
        val cancelled = AtomicBoolean(false)
        cont.invokeOnCancellation {
            cancelled.set(true)
            // Runs on the caller's thread; LlmModel.cancel is safe from any thread.
            runningModel?.cancel()
        }
        executor.execute {
            val result = runCatching {
                if (cancelled.get()) throw CancellationException()
                val m = loadedModel()
                runningModel = m
                try {
                    m.generate(prompt, maxTokens, SAMPLING)
                } finally {
                    runningModel = null
                    lastUsed = SystemClock.elapsedRealtime()
                }
            }.recoverCatching { e -> throw if (e is LlmCancelledException) CancellationException() else e }
            cont.resumeWith(result)
        }
    }

    /** On [executor]. */
    private fun loadedModel(): LlmModel {
        model?.let { return it }
        check(modelFile.isFile) { "The AI translation model is not installed." }
        val started = SystemClock.elapsedRealtime()
        return LlmModel.load(modelFile, threadCount(), CONTEXT_SIZE).also {
            model = it
            lastUsed = SystemClock.elapsedRealtime()
            Log.i(TAG, "loaded AI translation model in ${lastUsed - started} ms")
        }
    }

    override fun prepare() {
        if (_state.value !is AiModelState.Installed) return
        executor.execute { runCatching { loadedModel() }.onFailure { Log.w(TAG, "warm-up failed", it) } }
    }

    // ------------------------------------------------------------------ install / remove

    /** Copies a .gguf file picked by the user into app storage, after checking it is Hy-MT2. */
    fun import(uri: Uri) {
        if (_state.value is AiModelState.Importing || _state.value is AiModelState.Unsupported) return
        importCancelled.set(false)
        scope.launch(Dispatchers.IO) {
            val part = File(modelDir, "$MODEL_FILE_NAME.part")
            try {
                val resolver = context.contentResolver
                val total = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                } ?: -1L
                resolver.openInputStream(uri)?.use { input ->
                    checkModel(runCatching { GgufHeader.read(input) }.getOrElse { throw IOException(context.getString(R.string.ai_import_not_gguf)) })
                }
                    ?: throw IOException(context.getString(R.string.ai_import_unreadable))
                modelDir.mkdirs()
                if (total > 0 && modelDir.usableSpace < total + SPACE_MARGIN) {
                    throw IOException(context.getString(R.string.ai_import_no_space, (total + SPACE_MARGIN) / GB))
                }
                _state.value = AiModelState.Importing(0, total)
                var copied = 0L
                var lastReport = 0L
                resolver.openInputStream(uri)!!.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        while (true) {
                            if (importCancelled.get()) throw CancellationException()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                _state.value = AiModelState.Importing(copied, total)
                            }
                        }
                    }
                }
                if (total > 0 && copied != total) throw IOException(context.getString(R.string.ai_import_incomplete))
                onExecutor {
                    model?.close()
                    model = null
                    modelFile.delete()
                    if (!part.renameTo(modelFile)) throw IOException(context.getString(R.string.ai_import_save_failed))
                }
                _state.value = AiModelState.Installed(modelFile.length())
            } catch (e: CancellationException) {
                part.delete()
                _state.value = AiModelState.NotInstalled()
            } catch (e: Exception) {
                Log.w(TAG, "model import failed", e)
                part.delete()
                _state.value = AiModelState.NotInstalled(e.message ?: context.getString(R.string.ai_import_failed))
            }
        }
    }

    fun cancelImport() = importCancelled.set(true)

    fun remove() {
        scope.launch(Dispatchers.IO) {
            onExecutor {
                model?.close()
                model = null
                modelFile.delete()
            }
            _state.value = initialState()
        }
    }

    private fun checkModel(info: GgufInfo) {
        if (info.architecture != EXPECTED_ARCHITECTURE) {
            throw IOException(context.getString(R.string.ai_import_not_hymt, info.architecture ?: "?"))
        }
    }

    private suspend fun onExecutor(block: () -> Unit) = suspendCancellableCoroutine { cont ->
        executor.execute { cont.resumeWith(runCatching(block)) }
    }

    private fun threadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Big cores only: using the efficiency cores too makes token generation slower.
        return if (cores >= 8) 4 else (cores / 2).coerceAtLeast(2)
    }

    private val Language.isChinese: Boolean
        get() = this == Language.CHINESE_SIMPLIFIED || this == Language.CHINESE_TRADITIONAL

    companion object {
        /** The official Q4_K_M build (1.13 GB) is recommended; the other official GGUF builds also work. */
        const val RECOMMENDED_FILE = "Hy-MT2-1.8B-Q4_K_M.gguf"
        private const val TAG = "HyMtTranslator"
        private const val MODEL_FILE_NAME = "hy-mt2.gguf"
        private const val EXPECTED_ARCHITECTURE = "hunyuan-dense"
        private const val CONTEXT_SIZE = 2048
        /**
         * Off: in a test of short replies with the previous sentence as "background" (2026-10-22,
         * Q4_K_M), 2 of 6 came back as a translation of the background sentence instead, while all
         * 6 were correct with the default template. The context is still passed in for later tests.
         */
        private const val USE_CONTEXT = false
        private const val TRANSLATE_TIMEOUT_MS = 15_000L
        private const val IDLE_UNLOAD_MS = 5 * 60 * 1000L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val SPACE_MARGIN = 64L * 1024 * 1024
        private const val GB = 1024.0 * 1024 * 1024
        /** Phones sold as 6 GB report a little less. */
        private const val MIN_TOTAL_MEMORY = 5L * 1024 * 1024 * 1024
        /** Recommended by the Hy-MT2 model card for the 1.8B model. */
        private val SAMPLING = SamplingParams(temperature = 0.7f, topP = 0.6f, topK = 20, repeatPenalty = 1.05f)
    }
}
