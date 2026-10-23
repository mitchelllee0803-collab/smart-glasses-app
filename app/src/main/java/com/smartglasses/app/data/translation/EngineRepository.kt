package com.smartglasses.app.data.translation

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.offlinetranslate.app.engine.OfflineEngine
import com.offlinetranslate.app.engine.PickedFolderSource
import com.offlinetranslate.engine.EngineException
import com.offlinetranslate.engine.ErrorCode
import com.smartglasses.app.R
import com.smartglasses.app.data.speech.SAMPLE_RATE
import com.smartglasses.app.domain.EngineError
import com.smartglasses.app.domain.EngineStatus
import com.smartglasses.app.domain.ModelImportState
import com.smartglasses.app.domain.OfflineSpeechEngine
import com.smartglasses.app.domain.model.Language
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Translation project's offline engine (CTranslate2 + SentencePiece + OpenCC translation,
 * SenseVoice speech recognition) behind coroutines. Requests run on the engine's own threads;
 * a cancelled coroutine cancels its engine request.
 */
@Singleton
class EngineRepository @Inject constructor(@ApplicationContext private val context: Context) : OfflineSpeechEngine {
    private val engine = OfflineEngine(context)
    private val requestIds = AtomicLong()
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Starting)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()
    private val _importState = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    override val importState: StateFlow<ModelImportState> = _importState.asStateFlow()

    init {
        engine.start { publishStatus() }
    }

    private fun publishStatus() {
        _status.value = when {
            engine.ready -> EngineStatus.Ready(engine.translationReady, engine.speechReady, engine.problems())
            engine.lastError != null -> EngineStatus.Failed(engine.lastError!!)
            else -> EngineStatus.Starting
        }
    }

    override suspend fun prepareSpeech(language: Language) {
        suspendCancellableCoroutine { cont ->
            engine.prepareSpeech(language.engineCode) { r ->
                r.exceptionOrNull()?.let { Log.w(TAG, "speech model not prepared: ${it.message}") }
                cont.resume(Unit)
            }
        }
    }

    override suspend fun transcribe(pcm16: ByteArray, language: Language, partial: Boolean): String =
        request { id, done -> engine.transcribe(id, pcm16, SAMPLE_RATE, language.engineCode, partial, done) }
            .let { it["text"] as? String ?: "" }

    override suspend fun translate(text: String, source: Language, target: Language): String =
        request { id, done -> engine.translate(id, text, source.engineCode, target.engineCode, done) }
            .let { it["translatedText"] as? String ?: "" }

    private suspend fun request(
        call: (String, (Result<Map<String, Any?>>) -> Unit) -> Unit,
    ): Map<String, Any?> = suspendCancellableCoroutine { cont ->
        val id = "r-${requestIds.incrementAndGet()}"
        cont.invokeOnCancellation { engine.cancel(id) }
        call(id) { result ->
            result.fold(
                onSuccess = { cont.resume(it) },
                onFailure = { cont.resumeWith(Result.failure(it.toEngineError())) },
            )
        }
    }

    override fun importModels(folder: Uri) {
        if (engine.isImporting) return
        var lastProgressAt = 0L
        _importState.value = ModelImportState.Running(0, 0, null)
        engine.importModels(
            openSource = { PickedFolderSource.open(context, folder) },
            progress = { p ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= PROGRESS_INTERVAL_MS || p.copiedBytes == p.totalBytes) {
                    lastProgressAt = now
                    _importState.value = ModelImportState.Running(p.copiedBytes, p.totalBytes, p.file)
                }
            },
        ) { result ->
            _importState.value = result.fold(
                onSuccess = {
                    @Suppress("UNCHECKED_CAST") // StagedImport.toMap() in the engine module
                    ModelImportState.Done(
                        importedFiles = it["importedFiles"] as? Int ?: 0,
                        alreadyInstalled = it["alreadyInstalled"] as? Int ?: 0,
                        missing = it["missing"] as? List<String> ?: emptyList(),
                    )
                },
                onFailure = {
                    val error = it.toEngineError()
                    ModelImportState.Failed(error.message.orEmpty(), cancelled = error.code == ErrorCode.CANCELLED)
                },
            )
            publishStatus()
        }
    }

    override fun cancelImport() = engine.cancelImport()

    private fun Throwable.toEngineError(): EngineError = when (this) {
        is EngineError -> this
        is EngineException -> EngineError(code, message.orEmpty())
        is OutOfMemoryError -> EngineError(ErrorCode.INTERNAL_ERROR, context.getString(R.string.error_engine_memory))
        else -> {
            Log.e(TAG, "engine request failed", this)
            EngineError(ErrorCode.INTERNAL_ERROR, context.getString(R.string.error_engine_internal, javaClass.simpleName))
        }
    }

    private companion object {
        const val TAG = "EngineRepository"
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
