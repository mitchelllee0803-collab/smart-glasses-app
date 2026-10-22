package com.smartglasses.app.data.translation

import android.util.Log
import com.smartglasses.app.data.speech.AudioInput
import com.smartglasses.app.data.speech.GlassesMicUnavailableException
import com.smartglasses.app.data.speech.MicrophoneException
import com.smartglasses.app.data.speech.PhraseSegmenter
import com.smartglasses.app.data.speech.SegmenterEvent
import com.smartglasses.app.data.speech.rms
import com.smartglasses.app.data.speech.toPcm16
import com.smartglasses.app.di.ApplicationScope
import com.smartglasses.app.domain.EngineError
import com.smartglasses.app.domain.EngineStatus
import com.smartglasses.app.domain.OfflineSpeechEngine
import com.smartglasses.app.domain.SubtitleSink
import com.smartglasses.app.domain.SubtitleUpdate
import com.smartglasses.app.domain.TranscriptArchive
import com.smartglasses.app.domain.model.Language
import com.smartglasses.app.domain.model.MicrophoneSource
import com.smartglasses.app.domain.model.TranscriptLine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionState(
    val listening: Boolean = false,
    /** Listening stopped; the last phrases are still being recognised and translated. */
    val finishing: Boolean = false,
    /** 0..1 microphone level for the meter. */
    val level: Float = 0f,
    val lines: List<TranscriptLine> = emptyList(),
    val error: SessionError? = null,
)

sealed interface SessionError {
    data object ModelsMissing : SessionError
    data class Microphone(val message: String) : SessionError
    /** The glasses' microphone could not be opened; switching to the phone microphone helps. */
    data class GlassesMicrophone(val message: String) : SessionError
    data class Engine(val message: String) : SessionError
}

/** How a session should run; read once when listening starts. */
data class SessionOptions(
    val source: Language,
    val target: Language,
    val liveTranslation: Boolean,
    val microphone: MicrophoneSource,
    /** Picks the glasses' Bluetooth audio device when several headsets are connected. */
    val glassesAddress: String?,
)

/**
 * Microphone -> phrases -> offline recognition -> offline translation -> screen + [SubtitleSink].
 * Ported from the Translation app's useVoiceInput: live (partial) recognition is skipped while one
 * is running; finished phrases are recognised and translated strictly in speaking order; results
 * of an abandoned session or a superseded phrase are dropped. All bookkeeping runs on one
 * confined dispatcher; the engine work runs on the engine's threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TranslationSessionManager internal constructor(
    private val engine: OfflineSpeechEngine,
    private val audio: AudioInput,
    private val sink: SubtitleSink,
    private val archive: TranscriptArchive,
    private val scope: CoroutineScope,
    private val confined: CoroutineDispatcher,
) {
    @Inject constructor(
        engine: OfflineSpeechEngine,
        audio: AudioInput,
        sink: SubtitleSink,
        archive: TranscriptArchive,
        @ApplicationScope scope: CoroutineScope,
    ) : this(engine, audio, sink, archive, scope, Dispatchers.Default.limitedParallelism(1))

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private class FinalPhrase(val audio: FloatArray, val lineId: Long, val generation: Int)

    private var options: SessionOptions? = null
    private var generation = 0
    private var nextLineId = 1L
    /** Line showing the live text of the phrase being spoken. */
    private var liveLine: Long? = null
    /** Bumped whenever a phrase is handed over: live results for older phrases are dropped. */
    private var phraseToken = 0
    private var partialBusy = false
    private var partialTranslationBusy = false
    private var segmenter: PhraseSegmenter? = null
    private var captureJob: Job? = null
    private var finals: Channel<FinalPhrase>? = null
    /** Changes with every newSession(); the saved record belongs to one page. */
    private var sessionEpoch = 0
    private var sessionStartedAt = System.currentTimeMillis()
    private var recordId: Long? = null

    fun startListening(options: SessionOptions) {
        scope.launch(confined) {
            if (captureJob != null) return@launch
            val status = engine.status.value
            if (status !is EngineStatus.Ready || !status.usable) {
                _state.update { it.copy(error = SessionError.ModelsMissing) }
                return@launch
            }
            this@TranslationSessionManager.options = options
            val gen = ++generation
            val seg = PhraseSegmenter().also { segmenter = it }
            val queue = Channel<FinalPhrase>(Channel.UNLIMITED).also { finals = it }
            _state.update { it.copy(listening = true, finishing = false, error = null) }
            launch { runCatching { engine.prepareSpeech(options.source) } } // load while the microphone starts

            val finalsJob = launch { for (phrase in queue) processFinal(phrase) }
            val job = launch {
                try {
                    audio.blocks(options.microphone, options.glassesAddress).collect { block -> onBlock(seg, block, gen) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GlassesMicUnavailableException) {
                    _state.update { it.copy(error = SessionError.GlassesMicrophone(e.message.orEmpty())) }
                } catch (e: Exception) {
                    // MicrophoneException, or AudioRecord's own exceptions (permission revoked, busy).
                    if (e !is MicrophoneException) Log.w(TAG, "microphone failed", e)
                    _state.update { it.copy(error = SessionError.Microphone(e.message.orEmpty())) }
                }
            }
            captureJob = job
            job.join()
            if (captureJob === job) captureJob = null
            // Microphone stopped (by the user or an error): finish the phrase being spoken. After
            // newSession() (generation changed) this run's state belongs to the old page: untouched.
            val current = gen == generation
            if (current && seg.inPhrase) handOver(seg, gen)
            queue.close()
            if (current) _state.update { it.copy(listening = false, finishing = true, level = 0f) }
            finalsJob.join()
            if (gen == generation) _state.update { it.copy(finishing = false) }
        }
    }

    /** Stops the microphone; phrases already spoken are still recognised, translated and saved. */
    fun stopListening() {
        scope.launch(confined) { captureJob?.cancel() }
    }

    /**
     * Starts a fresh, empty session page. Work still running for the previous session is
     * abandoned; its finished phrases are already saved.
     */
    fun newSession() {
        scope.launch(confined) {
            generation++
            sessionEpoch++
            captureJob?.cancel()
            captureJob = null
            liveLine = null
            recordId = null
            sessionStartedAt = System.currentTimeMillis()
            _state.value = SessionState()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Restarts listening with new options (e.g. another microphone) if a session is running.
     * Phrases already spoken are finished first.
     */
    fun restartIfListening(options: SessionOptions) {
        scope.launch(confined) {
            val running = captureJob ?: return@launch
            if (options == this@TranslationSessionManager.options) return@launch
            running.cancel()
            _state.first { !it.listening && !it.finishing }
            startListening(options)
        }
    }

    // ------------------------------------------------------------------ pipeline (confined)

    private fun onBlock(seg: PhraseSegmenter, block: FloatArray, gen: Int) {
        val r = rms(block)
        _state.update { it.copy(level = min(1f, sqrt(r) * LEVEL_GAIN)) }
        when (seg.push(block, r, AudioInput.BLOCK_MS)) {
            SegmenterEvent.PARTIAL -> if (seg.phraseSpeechMs >= MIN_PHRASE_SPEECH_MS) requestPartial(seg.phrase(), gen)
            SegmenterEvent.FINAL -> handOver(seg, gen)
            null -> Unit
        }
    }

    private fun requestPartial(phraseAudio: FloatArray, gen: Int) {
        if (partialBusy) return // the previous live update is still running: skip this one
        val opts = options ?: return
        partialBusy = true
        val token = phraseToken
        scope.launch(confined) {
            try {
                val text = engine.transcribe(toPcm16(phraseAudio), opts.source, partial = true)
                if (gen != generation || token != phraseToken || text.isBlank()) return@launch
                val id = liveLine ?: newLine().also { liveLine = it }
                val line = updateLine(id) { it.copy(original = text) } ?: return@launch
                pushToGlasses(line)
                if (opts.liveTranslation) requestPartialTranslation(id, text, token, gen, opts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineError) {
                // Live text is best effort; the final recognition reports real errors.
            } finally {
                partialBusy = false
            }
        }
    }

    private fun requestPartialTranslation(id: Long, text: String, token: Int, gen: Int, opts: SessionOptions) {
        if (partialTranslationBusy) return
        partialTranslationBusy = true
        scope.launch(confined) {
            try {
                val translation = engine.translate(text, opts.source, opts.target)
                if (gen != generation || token != phraseToken) return@launch
                // Never overwrite the final translation with a live one.
                val line = updateLine(id) { if (it.isFinal) it else it.copy(translation = translation) } ?: return@launch
                if (!line.isFinal) pushToGlasses(line)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineError) {
                // best effort, as above
            } finally {
                partialTranslationBusy = false
            }
        }
    }

    private fun handOver(seg: PhraseSegmenter, gen: Int) {
        val speechMs = seg.phraseSpeechMs
        val phraseAudio = seg.takePhrase()
        val id = liveLine
        liveLine = null
        phraseToken++
        if (speechMs < MIN_PHRASE_SPEECH_MS) { // a click or breath, not words
            id?.let(::removeLine)
            return
        }
        // The line id is fixed now, so lines stay in speaking order even if a later phrase
        // shows live text before this one is recognised.
        val lineId = id ?: newLine()
        updateLine(lineId) { it.copy(translating = true) }
        finals?.trySend(FinalPhrase(phraseAudio, lineId, gen))
    }

    private suspend fun processFinal(phrase: FinalPhrase) {
        val opts = options ?: return
        if (phrase.generation != generation) return
        val text = try {
            engine.transcribe(toPcm16(phrase.audio), opts.source, partial = false)
        } catch (e: EngineError) {
            removeLine(phrase.lineId)
            if (e.code != EngineError.NO_SPEECH && e.code != EngineError.CANCELLED) onEngineError(e)
            return
        }
        if (phrase.generation != generation) return
        updateLine(phrase.lineId) { it.copy(original = text, translating = true) }?.let(::pushToGlasses)
        val translation = try {
            engine.translate(text, opts.source, opts.target)
        } catch (e: EngineError) {
            if (e.code != EngineError.CANCELLED) onEngineError(e)
            ""
        }
        if (phrase.generation != generation) return
        val line = updateLine(phrase.lineId) { it.copy(translation = translation, translating = false, isFinal = true) } ?: return
        pushToGlasses(line)
        save(line, opts)
    }

    /** Saves a finished phrase; the session's record is created with its first phrase. */
    private suspend fun save(line: TranscriptLine, opts: SessionOptions) {
        if (line.original.isBlank()) return
        val epoch = sessionEpoch
        try {
            val id = recordId ?: archive.createRecord(sessionStartedAt, opts.source, opts.target).also {
                if (epoch == sessionEpoch) recordId = it // the page may have been replaced meanwhile
            }
            archive.saveLine(id, line)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "could not save phrase ${line.id}", e) // the session goes on; only history misses it
        }
    }

    private fun onEngineError(e: EngineError) {
        Log.w(TAG, "engine error ${e.code}")
        _state.update { it.copy(error = SessionError.Engine(e.message.orEmpty())) }
        if (e.isFatal) captureJob?.cancel()
    }

    private fun newLine(): Long {
        val id = nextLineId++
        _state.update { it.copy(lines = (it.lines + TranscriptLine(id, original = "")).takeLast(MAX_LINES)) }
        return id
    }

    /** Returns the updated line, or null if it is gone (cleared or trimmed). */
    private fun updateLine(id: Long, change: (TranscriptLine) -> TranscriptLine): TranscriptLine? {
        var updated: TranscriptLine? = null
        _state.update { s ->
            s.copy(lines = s.lines.map { if (it.id == id) change(it).also { u -> updated = u } else it })
        }
        return updated
    }

    private fun removeLine(id: Long) {
        _state.update { s -> s.copy(lines = s.lines.filterNot { it.id == id }) }
        sink.remove(id)
    }

    private fun pushToGlasses(line: TranscriptLine) {
        if (line.original.isBlank()) return
        sink.submit(SubtitleUpdate(line.id, line.original, line.translation, line.isFinal))
    }

    private companion object {
        const val TAG = "TranslationSession"
        /** Shorter bursts (a click, a breath) become filler words in the model: skipped. */
        const val MIN_PHRASE_SPEECH_MS = 500
        const val MAX_LINES = 200
        const val LEVEL_GAIN = 3f
    }
}
