package com.offlinetranslate.engine

import com.offlinetranslate.engine.Golden.strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    private val text = Golden.json("text.json")

    @Test fun `sentence splitting matches the Python worker`() {
        val cases = text.getJSONArray("split")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("input #$i: ${c.getString("input").take(40)}", c.getJSONArray("output").strings(), SentenceSplit.split(c.getString("input")))
        }
    }

    @Test fun `terminal stop detection matches the Python worker`() {
        val cases = text.getJSONArray("terminalStop")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("input"), c.getBoolean("output"), PostProcess.needsTerminalStop(c.getString("input")))
        }
    }

    @Test fun `Chinese punctuation normalisation matches the Python worker`() {
        val cases = text.getJSONArray("chinesePunct")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("output"), PostProcess.normalizeChinesePunctuation(c.getString("input")))
        }
    }

    @Test fun `language detection matches the Python worker`() {
        val tables = text.getJSONObject("openccChars").let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { t -> t.keys().asSequence().associateWith { t.getString(it) } } }
        }
        val converter = TableConverter(tables)
        val cases = text.getJSONArray("detect")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val expected = c.getJSONObject("output")
            val actual = Detect.detect(c.getString("input"), converter)
            val input = c.getString("input")
            assertEquals(input, if (expected.isNull("language")) null else expected.getString("language"), actual["language"])
            assertEquals(input, expected.getBoolean("ambiguous"), actual["ambiguous"])
            assertEquals(input, expected.getDouble("confidence"), actual["confidence"] as Double, 1e-9)
            assertEquals(input, expected.getString("reason"), actual["reason"])
        }
    }

    @Test fun `all twelve directions route exactly like Windows`() {
        val expected = mapOf(
            "en>ko" to "en,ko", "ko>en" to "ko,en", "en>zh-Hans" to "en,zh-Hans", "zh-Hans>en" to "zh-Hans,en",
            "en>zh-Hant" to "en,zh-Hant", "zh-Hant>en" to "zh-Hant,en", "ko>zh-Hans" to "ko,en,zh-Hans",
            "zh-Hans>ko" to "zh-Hans,en,ko", "ko>zh-Hant" to "ko,en,zh-Hant", "zh-Hant>ko" to "zh-Hant,en,ko",
            "zh-Hans>zh-Hant" to "zh-Hans,zh-Hant", "zh-Hant>zh-Hans" to "zh-Hant,zh-Hans",
        )
        var pairs = 0
        for (s in Lang.entries) for (t in Lang.entries) {
            if (s == t) continue
            pairs++
            val plan = planRoute(s, t)
            assertEquals(expected.getValue("${s.code}>${t.code}"), plan.route.joinToString(",") { it.code })
            assertEquals(plan.route.size == 3, plan.pivoted)
        }
        assertEquals(12, pairs)
        assertEquals(listOf("ko_en", "en_zt"), planRoute(Lang.KO, Lang.ZH_HANT).requiredPackages)
        assertEquals("s2t.json", (planRoute(Lang.ZH_HANS, Lang.ZH_HANT).steps.single() as Step.OpenCC).config)
        assertTrue(planRoute(Lang.EN, Lang.EN).steps.isEmpty())
    }

    @Test fun `whitespace class equals Python's Unicode whitespace`() {
        val python = listOf(0x9, 0xa, 0xb, 0xc, 0xd, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680) +
            (0x2000..0x200a) + listOf(0x2028, 0x2029, 0x202f, 0x205f, 0x3000)
        val re = Regex(UNICODE_SPACE)
        assertEquals(python, (0 until 0x10000).filter { re.matches(it.toChar().toString()) })
        assertEquals(listOf("Hello.", "World"), SentenceSplit.split("Hello.　World"))
    }

    @Test fun `engine regexes use no inline flags (Android's ICU engine rejects some, e g (?U))`() {
        val src = java.io.File("src/main/java/com/offlinetranslate/engine")
        val offenders = src.listFiles()!!.filter { it.name.endsWith(".kt") }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> Regex("""Regex\(.*\(\?[a-zA-Z]""").containsMatchIn(l) }.map { "${f.name}:${it.index + 1}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun `unsupported languages and invalid text are rejected`() {
        for (bad in listOf("zh", "zt", "ja", "", null, "EN")) {
            assertEquals(ErrorCode.UNSUPPORTED_LANGUAGE, assertThrows(EngineException::class.java) { Lang.of(bad) }.code)
        }
        assertEquals("a\nb\nc", validateText("a\r\nb\rc"))
        assertEquals("emoji 🎉 한국어", validateText("emoji 🎉 한국어"))
        assertEquals(ErrorCode.INVALID_TEXT, assertThrows(EngineException::class.java) { validateText(42) }.code)
        assertEquals(ErrorCode.INVALID_TEXT, assertThrows(EngineException::class.java) { validateText("bad\u0000") }.code)
        assertEquals(ErrorCode.INVALID_TEXT, assertThrows(EngineException::class.java) { validateText("\ud800 lone") }.code)
        assertEquals(ErrorCode.TEXT_TOO_LONG, assertThrows(EngineException::class.java) { validateText("x".repeat(5001)) }.code)
        assertFalse(PostProcess.needsTerminalStop("   "))
    }
}
