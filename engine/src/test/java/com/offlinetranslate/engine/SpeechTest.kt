package com.offlinetranslate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    private val golden = Golden.json("speech.json")
    private val conversions = golden.getJSONObject("conversions").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }

    /** OpenCC as the Python worker applied it to exactly these texts. */
    private val toScript: (String, Lang) -> String = { text, lang ->
        conversions["${lang.code}|$text"] ?: error("no reference conversion for ${lang.code}|$text")
    }

    @Test fun `text and language rules match the Python worker on real SenseVoice output`() {
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val requested = c.getString("requested").let { if (it == "auto") null else Lang.of(it) }
            val partial = c.getBoolean("partial")
            val expected = c.getJSONObject("expected")
            val label = "${c.getString("source")} ${c.getString("lang")} '${c.getString("text")}' requested=${c.getString("requested")} partial=$partial"
            if (expected.has("error")) {
                val e = assertThrows(label, EngineException::class.java) {
                    Speech.finishText(c.getString("text"), c.getString("lang"), requested, partial, toScript)
                }
                assertEquals(label, expected.getString("error"), e.code)
            } else {
                val (text, lang) = Speech.finishText(c.getString("text"), c.getString("lang"), requested, partial, toScript)
                assertEquals(label, expected.getString("text"), text)
                assertEquals(label, if (expected.isNull("language")) null else expected.getString("language"), lang?.code)
            }
        }
        assertEquals(210, cases.length())
    }

    @Test fun `clean-up and filler detection match Python`() {
        val clean = golden.getJSONArray("cleanText")
        for (i in 0 until clean.length()) {
            val c = clean.getJSONObject(i)
            assertEquals(c.getString("output"), Speech.cleanText(c.getString("input")))
        }
        val fillers = golden.getJSONArray("isFiller")
        for (i in 0 until fillers.length()) {
            val c = fillers.getJSONObject(i)
            assertEquals(c.getString("input"), c.getBoolean("output"), Speech.isFiller(c.getString("input")))
        }
    }

    @Test fun `the recognizer is fixed to the selected language`() {
        assertEquals("auto", Speech.senseVoiceLanguage(null))
        assertEquals("en", Speech.senseVoiceLanguage(Lang.EN))
        assertEquals("ko", Speech.senseVoiceLanguage(Lang.KO))
        assertEquals("zh", Speech.senseVoiceLanguage(Lang.ZH_HANS))
        assertEquals("zh", Speech.senseVoiceLanguage(Lang.ZH_HANT))
    }

    @Test fun `PCM validation`() {
        val oneSecond = ByteArray(32000)
        assertEquals(16000, Speech.decodePcm16(oneSecond, 16000).size)
        assertEquals(ErrorCode.INVALID_AUDIO, assertThrows(EngineException::class.java) { Speech.decodePcm16(oneSecond, 44100) }.code)
        assertEquals(ErrorCode.INVALID_AUDIO, assertThrows(EngineException::class.java) { Speech.decodePcm16(ByteArray(3), 16000) }.code)
        assertEquals(ErrorCode.INVALID_AUDIO, assertThrows(EngineException::class.java) { Speech.decodePcm16(ByteArray(0), 16000) }.code)
        assertEquals(ErrorCode.NO_SPEECH, assertThrows(EngineException::class.java) { Speech.decodePcm16(ByteArray(3200), 16000) }.code)
        assertEquals(ErrorCode.AUDIO_TOO_LONG, assertThrows(EngineException::class.java) { Speech.decodePcm16(ByteArray(32000 * 122), 16000) }.code)
        val loud = byteArrayOf(0xff.toByte(), 0x7f) // 32767
        val pcm = ByteArray(9600) { loud[it % 2] }
        assertEquals(32767 / 32768f, Speech.decodePcm16(pcm, 16000)[0], 1e-6f)
    }

    // --------------------------------------------------- service (fake recognizer)
    private val speech = FloatArray(16000 * 3) { (Math.sin(it / 5.0) * 0.2).toFloat() }

    private class Fake(val text: String, val tag: String) : SpeechBackend {
        var calls = 0
        override fun recognize(audio: FloatArray): Pair<String, String> { calls++; return text to tag }
    }

    @Test fun `a final phrase returns the same fields as the Windows worker`() {
        val r = SpeechService(Fake("Hello world.", "<|en|>"), "sense-voice-small", { t, _ -> t }).transcribe(speech, Lang.EN)
        assertEquals("Hello world.", r["text"])
        assertEquals("en", r["language"])
        assertEquals("en", r["requestedLanguage"])
        assertEquals(3.0, r["audioSeconds"])
        assertEquals("sense-voice-small", r["model"])
        assertEquals(false, r["partial"])
        assertEquals(setOf("text", "language", "requestedLanguage", "audioSeconds", "elapsedMs", "model", "partial"), r.keys)
    }

    @Test fun `silence is no speech for a final phrase and simply empty for a live update`() {
        val fake = Fake("그.", "<|ko|>")
        val service = SpeechService(fake, "m", { t, _ -> t })
        assertEquals(ErrorCode.NO_SPEECH, assertThrows(EngineException::class.java) { service.transcribe(speech, null) }.code)
        val live = service.transcribe(speech, null, partial = true)
        assertEquals("", live["text"])
        assertNull(live["language"])
        assertEquals("auto", live["requestedLanguage"])
        // Digital silence never reaches the recognizer.
        val calls = fake.calls
        assertEquals(ErrorCode.NO_SPEECH, assertThrows(EngineException::class.java) { service.transcribe(FloatArray(16000), Lang.EN) }.code)
        assertEquals("", service.transcribe(FloatArray(16000), Lang.EN, partial = true)["text"])
        assertEquals(calls, fake.calls)
    }

    @Test fun `Mandarin follows the selected script, Detect language writes Simplified`() {
        val scripts = mutableListOf<Lang>()
        val service = SpeechService(Fake("你好 世界", "<|zh|>"), "m", { t, l -> scripts += l; "[${l.code}]$t" })
        assertEquals("[zh-Hans]你好世界", service.transcribe(speech, null)["text"])
        assertEquals("[zh-Hant]你好世界", service.transcribe(speech, Lang.ZH_HANT)["text"])
        assertEquals(listOf(Lang.ZH_HANS, Lang.ZH_HANT), scripts)
    }

    @Test fun `cancellation is checked before recognition`() {
        val fake = Fake("Hello", "<|en|>")
        val cancelled = CancelCheck { throw EngineException(ErrorCode.CANCELLED, "cancelled") }
        assertEquals(ErrorCode.CANCELLED, assertThrows(EngineException::class.java) {
            SpeechService(fake, "m", { t, _ -> t }).transcribe(speech, Lang.EN, cancel = cancelled)
        }.code)
        assertTrue(fake.calls == 0)
    }
}
