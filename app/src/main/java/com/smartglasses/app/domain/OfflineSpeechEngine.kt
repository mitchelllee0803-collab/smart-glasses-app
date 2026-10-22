package com.smartglasses.app.domain

import android.net.Uri
import com.smartglasses.app.domain.model.Language
import kotlinx.coroutines.flow.StateFlow

/** Offline speech recognition and translation (the reused Translation engine). */
interface OfflineSpeechEngine {
    val status: StateFlow<EngineStatus>
    val importState: StateFlow<ModelImportState>

    /** Loads the speech model ahead of the first phrase. Best effort. */
    suspend fun prepareSpeech(language: Language)

    /**
     * 16 kHz mono PCM16 (little endian) -> text. A partial result may be empty; a final one
     * throws [EngineError] with code NO_SPEECH when nothing was said.
     */
    suspend fun transcribe(pcm16: ByteArray, language: Language, partial: Boolean): String

    suspend fun translate(text: String, source: Language, target: Language): String

    fun importModels(folder: Uri)

    fun cancelImport()
}

sealed interface EngineStatus {
    data object Starting : EngineStatus
    data class Ready(val translationReady: Boolean, val speechReady: Boolean, val problems: List<String>) : EngineStatus {
        val usable: Boolean get() = translationReady && speechReady
    }
    data class Failed(val message: String) : EngineStatus
}

sealed interface ModelImportState {
    data object Idle : ModelImportState
    data class Running(val copiedBytes: Long, val totalBytes: Long, val file: String?) : ModelImportState
    data class Done(val importedFiles: Int, val alreadyInstalled: Int, val missing: List<String>) : ModelImportState
    data class Failed(val message: String, val cancelled: Boolean) : ModelImportState
}

/** Engine failure with the engine's error code (same codes as the Translation project). */
class EngineError(val code: String, message: String) : Exception(message) {
    val isFatal: Boolean get() = code in FATAL_CODES

    companion object {
        const val NO_SPEECH = "NO_SPEECH"
        const val CANCELLED = "CANCELLED"
        private val FATAL_CODES = setOf("MODEL_UNAVAILABLE", "WORKER_FAILED", "INTERNAL_ERROR", "RESOURCE_MISSING")
    }
}
