package com.smartglasses.app.data.translation

import android.net.Uri
import com.smartglasses.app.data.speech.AudioInput
import com.smartglasses.app.data.speech.GlassesMicUnavailableException
import com.smartglasses.app.domain.AiTranslator
import com.smartglasses.app.domain.EngineError
import com.smartglasses.app.domain.EngineStatus
import com.smartglasses.app.domain.ModelImportState
import com.smartglasses.app.domain.OfflineSpeechEngine
import com.smartglasses.app.domain.SubtitleConfig
import com.smartglasses.app.domain.SubtitleSink
import com.smartglasses.app.domain.SubtitleSinkStatus
import com.smartglasses.app.domain.SubtitleUpdate
import com.smartglasses.app.domain.TranscriptArchive
import com.smartglasses.app.domain.TranscriptCorrector
import com.smartglasses.app.domain.model.TranscriptLine
import com.smartglasses.app.domain.model.Language
import com.smartglasses.app.domain.model.MicrophoneSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSessionManagerTest {

    private class FakeEngine(ready: Boolean = true) : OfflineSpeechEngine {
        override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.Ready(ready, ready, emptyList()))
        override val importState: StateFlow<ModelImportState> = MutableStateFlow(ModelImportState.Idle)
        /** Final transcripts in order; each final call takes the next one. */
        val finals = ArrayDeque<String>()
        var finalDelays = ArrayDeque<Long>()
        val partials = mutableListOf<Boolean>()

        override suspend fun prepareSpeech(language: Language) = Unit

        override suspend fun transcribe(pcm16: ByteArray, language: Language, partial: Boolean): String {
            partials += partial
            if (partial) return "live"
            delay(finalDelays.removeFirstOrNull() ?: 10)
            val text = finals.removeFirstOrNull() ?: throw EngineError(EngineError.NO_SPEECH, "no speech")
            return text
        }

        override suspend fun translate(text: String, source: Language, target: Language): String {
            delay(5)
            return "${target.engineCode}:$text"
        }

        override fun importModels(folder: Uri) = Unit
        override fun cancelImport() = Unit
    }

    private class RecordingSink : SubtitleSink {
        override val status: StateFlow<SubtitleSinkStatus> = MutableStateFlow(SubtitleSinkStatus.Active)
        val updates = mutableListOf<SubtitleUpdate>()
        val removed = mutableListOf<Long>()
        override fun start(config: SubtitleConfig) = Unit
        override fun updateConfig(config: SubtitleConfig) = Unit
        override fun reopen() = Unit
        override fun submit(update: SubtitleUpdate) {
            updates += update
        }
        override fun remove(lineId: Long) {
            removed += lineId
        }
        override fun stop() = Unit
    }

    /** Scripted microphone: quiet, then (speech, pause) per phrase, then the stream ends. */
    private class ScriptedAudio(private val phrases: List<Int>) : AudioInput {
        val sources = mutableListOf<MicrophoneSource>()

        override fun blocks(source: MicrophoneSource, glassesAddress: String?) = flow {
            sources += source
            fun block(level: Float) = FloatArray(AudioInput.BLOCK_SAMPLES) { if (it % 2 == 0) level else -level }
            repeat(5) { emit(block(0.001f)) }
            for (speechBlocks in phrases) {
                repeat(speechBlocks) { emit(block(0.2f)) }
                repeat(10) { emit(block(0.001f)) }
            }
        }
    }

    private val options = SessionOptions(
        Language.ENGLISH, Language.KOREAN, liveTranslation = false, microphone = MicrophoneSource.GLASSES, glassesAddress = null,
    )

    /** In-memory history: record id -> saved phrases (original, translation). */
    private class FakeArchive : TranscriptArchive {
        val records = linkedMapOf<Long, MutableList<Pair<String, String>>>()

        override suspend fun createRecord(startedAt: Long, source: Language, target: Language): Long {
            val id = records.size + 1L
            records[id] = mutableListOf()
            return id
        }

        override suspend fun saveLine(recordId: Long, line: TranscriptLine) {
            records.getValue(recordId) += line.original to line.translation
        }
    }

    /** AI translator stand-in: "ai:<text>", or failure / hang as configured. Records the context it got. */
    private class FakeAi(var installed: Boolean = true, var fail: Boolean = false, var hangMs: Long = 0) : AiTranslator {
        val contexts = mutableListOf<List<String>>()
        var prepared = 0
        override fun handles(source: Language, target: Language) = installed
        override fun prepare() {
            prepared++
        }
        override suspend fun translate(text: String, source: Language, target: Language, context: List<String>): String {
            contexts += context
            if (hangMs > 0) withTimeout(100) { delay(hangMs) } // like the real translator's timeout
            if (fail) throw IllegalStateException("model failed")
            return "ai:$text"
        }
    }

    private class UpperCaseCorrector : TranscriptCorrector {
        override suspend fun correct(text: String, language: Language, context: List<String>) = text.uppercase()
    }

    private fun TestScope.manager(
        engine: OfflineSpeechEngine,
        audio: AudioInput,
        sink: SubtitleSink,
        archive: TranscriptArchive = FakeArchive(),
        ai: AiTranslator = FakeAi(installed = false),
        corrector: TranscriptCorrector = NoOpTranscriptCorrector(),
    ) = TranslationSessionManager(engine, audio, sink, archive, ai, corrector, this, StandardTestDispatcher(testScheduler))

    @Test
    fun `phrase is recognised, translated and sent to the glasses as final`() = runTest {
        val engine = FakeEngine().apply { finals += "hello world" }
        val sink = RecordingSink()
        val session = manager(engine, ScriptedAudio(listOf(15)), sink)

        session.startListening(options)
        testScheduler.advanceUntilIdle()

        val lines = session.state.value.lines
        assertEquals(1, lines.size)
        assertEquals("hello world", lines[0].original)
        assertEquals("ko:hello world", lines[0].translation)
        assertTrue(lines[0].isFinal)
        assertTrue(engine.partials.contains(true)) // live text was requested while speaking
        val last = sink.updates.last()
        assertTrue(last.isFinal)
        assertEquals("ko:hello world", last.translation)
        assertFalse(session.state.value.listening)
        assertFalse(session.state.value.finishing)
    }

    @Test
    fun `lines keep speaking order when an earlier phrase is slow to recognise`() = runTest {
        val engine = FakeEngine().apply {
            finals += listOf("first", "second")
            finalDelays += listOf(3_000L, 10L)
        }
        val session = manager(engine, ScriptedAudio(listOf(8, 8)), RecordingSink())

        session.startListening(options)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("first", "second"), session.state.value.lines.map { it.original })
        assertTrue(session.state.value.lines.all { it.isFinal })
    }

    @Test
    fun `a phrase without speech is removed from screen and glasses`() = runTest {
        val engine = FakeEngine() // no final transcripts: NO_SPEECH
        val sink = RecordingSink()
        val session = manager(engine, ScriptedAudio(listOf(10)), sink)

        session.startListening(options)
        testScheduler.advanceUntilIdle()

        assertTrue(session.state.value.lines.isEmpty())
        assertEquals(null, session.state.value.error) // NO_SPEECH is not an error
        assertTrue(sink.removed.isNotEmpty())
    }

    @Test
    fun `a new session starts empty and drops results of the previous page`() = runTest {
        val engine = FakeEngine().apply {
            finals += "too late"
            finalDelays += 10_000L
        }
        val archive = FakeArchive()
        val session = manager(engine, ScriptedAudio(listOf(10)), RecordingSink(), archive)

        session.startListening(options)
        testScheduler.advanceTimeBy(5_000)
        session.newSession()
        testScheduler.advanceUntilIdle()

        val state = session.state.value
        assertTrue(state.lines.isEmpty())
        assertFalse(state.listening)
        assertFalse(state.finishing) // the old page's stop must not leave the new page "finishing"
        assertTrue(archive.records.isEmpty())
    }

    @Test
    fun `finished phrases of a session are saved to one record, a new session gets a new record`() = runTest {
        val engine = FakeEngine().apply { finals += listOf("one", "two", "three") }
        val archive = FakeArchive()
        val session = manager(engine, ScriptedAudio(listOf(8, 8)), RecordingSink(), archive)

        session.newSession()
        session.startListening(options)
        testScheduler.advanceUntilIdle()
        assertEquals(mapOf(1L to listOf("one" to "ko:one", "two" to "ko:two")), archive.records)

        session.newSession()
        testScheduler.advanceUntilIdle()
        assertTrue(session.state.value.lines.isEmpty())

        val next = manager(engine, ScriptedAudio(listOf(8)), RecordingSink(), archive)
        next.newSession()
        next.startListening(options)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("three" to "ko:three"), archive.records[2L])
    }

    @Test
    fun `a page without speech saves nothing`() = runTest {
        val archive = FakeArchive()
        val session = manager(FakeEngine(), ScriptedAudio(listOf(10)), RecordingSink(), archive)
        session.newSession()
        session.startListening(options)
        testScheduler.advanceUntilIdle()
        assertTrue(archive.records.isEmpty())
    }

    @Test
    fun `listening needs the models`() = runTest {
        val session = manager(FakeEngine(ready = false), ScriptedAudio(emptyList()), RecordingSink())
        session.startListening(options)
        testScheduler.advanceUntilIdle()
        assertEquals(SessionError.ModelsMissing, session.state.value.error)
        assertFalse(session.state.value.listening)
    }

    /** Quiet microphone that runs until stopped; [failGlasses] simulates glasses without an audio link. */
    private class EndlessAudio(private val failGlasses: Boolean = false) : AudioInput {
        val sources = mutableListOf<MicrophoneSource>()

        override fun blocks(source: MicrophoneSource, glassesAddress: String?) = flow {
            sources += source
            if (failGlasses && source == MicrophoneSource.GLASSES) throw GlassesMicUnavailableException("not paired")
            while (true) {
                delay(AudioInput.BLOCK_MS.toLong())
                emit(FloatArray(AudioInput.BLOCK_SAMPLES))
            }
        }
    }

    @Test
    fun `glasses microphone failure is reported so the phone microphone can be offered`() = runTest {
        val audio = EndlessAudio(failGlasses = true)
        val session = manager(FakeEngine(), audio, RecordingSink())

        session.startListening(options)
        testScheduler.advanceUntilIdle()

        assertEquals(SessionError.GlassesMicrophone("not paired"), session.state.value.error)
        assertFalse(session.state.value.listening)

        session.startListening(options.copy(microphone = MicrophoneSource.PHONE))
        testScheduler.advanceTimeBy(1_000)
        assertTrue(session.state.value.listening)
        assertEquals(listOf(MicrophoneSource.GLASSES, MicrophoneSource.PHONE), audio.sources)

        session.stopListening()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `changing the microphone restarts a running session with the new source`() = runTest {
        val audio = EndlessAudio()
        val session = manager(FakeEngine(), audio, RecordingSink())

        session.startListening(options)
        testScheduler.advanceTimeBy(1_000)
        session.restartIfListening(options.copy(microphone = MicrophoneSource.PHONE))
        testScheduler.advanceTimeBy(1_000)

        assertEquals(listOf(MicrophoneSource.GLASSES, MicrophoneSource.PHONE), audio.sources)
        assertTrue(session.state.value.listening)

        session.restartIfListening(options.copy(microphone = MicrophoneSource.PHONE)) // same options: no restart
        testScheduler.advanceTimeBy(1_000)
        assertEquals(2, audio.sources.size)

        session.stopListening()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `finished sentences are translated by the AI model with earlier sentences as context`() = runTest {
        val engine = FakeEngine().apply { finals += listOf("one", "two", "three") }
        val ai = FakeAi()
        val sink = RecordingSink()
        val session = manager(engine, ScriptedAudio(listOf(8, 8, 8)), sink, ai = ai)

        session.startListening(options)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("ai:one", "ai:two", "ai:three"), session.state.value.lines.map { it.translation })
        assertEquals(listOf(emptyList(), listOf("one"), listOf("one", "two")), ai.contexts)
        assertEquals(1, ai.prepared) // model loaded while the microphone starts
        assertEquals("ai:three", sink.updates.last().translation)
    }

    @Test
    fun `live text keeps the fast engine, only the final translation is AI`() = runTest {
        val engine = FakeEngine().apply { finals += "hello" }
        val ai = FakeAi()
        val session = manager(engine, ScriptedAudio(listOf(15)), RecordingSink(), ai = ai)

        session.startListening(options.copy(liveTranslation = true))
        testScheduler.advanceUntilIdle()

        assertTrue(engine.partials.contains(true)) // live text came from the fast engine
        assertEquals(1, ai.contexts.size) // the AI model only ran for the finished sentence
        assertEquals("ai:hello", session.state.value.lines.single().translation)
    }

    @Test
    fun `AI failure or timeout falls back to the standard translator`() = runTest {
        for (ai in listOf(FakeAi(fail = true), FakeAi(hangMs = 10_000))) {
            val engine = FakeEngine().apply { finals += "hello" }
            val session = manager(engine, ScriptedAudio(listOf(10)), RecordingSink(), ai = ai)
            session.startListening(options)
            testScheduler.advanceUntilIdle()
            assertEquals("ko:hello", session.state.value.lines.single().translation)
            assertEquals(null, session.state.value.error)
        }
    }

    @Test
    fun `AI translation can be switched off`() = runTest {
        val engine = FakeEngine().apply { finals += "hello" }
        val ai = FakeAi()
        val session = manager(engine, ScriptedAudio(listOf(10)), RecordingSink(), ai = ai)
        session.startListening(options.copy(aiTranslation = false))
        testScheduler.advanceUntilIdle()
        assertEquals("ko:hello", session.state.value.lines.single().translation)
        assertTrue(ai.contexts.isEmpty())
    }

    @Test
    fun `the correction step changes the text before it is translated and shown`() = runTest {
        val engine = FakeEngine().apply { finals += "hello" }
        val sink = RecordingSink()
        val session = manager(engine, ScriptedAudio(listOf(10)), sink, ai = FakeAi(), corrector = UpperCaseCorrector())
        session.startListening(options)
        testScheduler.advanceUntilIdle()
        val line = session.state.value.lines.single()
        assertEquals("HELLO", line.original)
        assertEquals("ai:HELLO", line.translation)
        assertEquals("HELLO", sink.updates.last().original)
    }
}
