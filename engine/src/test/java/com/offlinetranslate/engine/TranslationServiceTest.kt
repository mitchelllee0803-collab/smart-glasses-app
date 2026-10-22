package com.offlinetranslate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationServiceTest {
    /** Records what the "model" receives and returns a tagged echo. */
    private class FakeModel(val id: String, val output: (String) -> String = { "[$id]$it" }) : SentenceTranslator {
        val received = mutableListOf<List<String>>()
        override fun translateSentences(sentences: List<String>): List<String> {
            received += sentences
            return sentences.map(output)
        }
    }

    private class FakeConverter : ChineseConverter {
        val calls = mutableListOf<String>()
        override fun convert(text: String, config: String): String {
            calls += config
            return when (config) {
                "s2t.json", "s2tw.json" -> text.replace("约", "約").replace("这", "這")
                "t2s.json" -> text.replace("約", "约").replace("這", "这")
                else -> text
            }
        }
    }

    private class AllModels(private val missing: Set<String> = emptySet()) : ModelAvailability {
        override fun requireArgos(packageId: String): String {
            if (packageId in missing) throw EngineException(ErrorCode.MODEL_UNAVAILABLE, "Translation model $packageId is not available")
            return "translate-$packageId"
        }
        override fun requireOpencc() {}
    }

    private val models = mutableMapOf<String, FakeModel>()
    private val converter = FakeConverter()
    private fun service(missing: Set<String> = emptySet(), output: (String, String) -> String = { id, s -> "[$id]$s" }) =
        TranslationService(AllModels(missing), converter, { id -> models.getOrPut(id) { FakeModel(id) { output(id, it) } } })

    @Test fun `short unpunctuated input gets a stop for the model that is removed again`() {
        val r = service { _, s -> if (s == "Hi.") "안녕하세요." else s }.translate("Hi", Lang.EN, Lang.KO)
        assertEquals(listOf(listOf("Hi.")), models.getValue("en_ko").received)
        assertEquals("안녕하세요", r.translatedText)
    }

    @Test fun `punctuated multi-sentence paragraphs are split and joined, lines preserved`() {
        val r = service { _, s -> s.uppercase() }.translate("Hello there. How are you?\n\nFine!", Lang.EN, Lang.KO)
        assertEquals(listOf(listOf("Hello there.", "How are you?"), listOf("Fine!")), models.getValue("en_ko").received)
        assertEquals("HELLO THERE. HOW ARE YOU?\n\nFINE!", r.translatedText)
    }

    @Test fun `pivot routes run two real model steps and report the English intermediate`() {
        val r = service().translate("저는 커피를 마십니다.", Lang.KO, Lang.ZH_HANS)
        assertTrue(r.pivoted)
        assertEquals(listOf(Lang.KO, Lang.EN, Lang.ZH_HANS), r.route)
        assertEquals(listOf("translate-ko_en", "translate-en_zh"), r.modelsUsed)
        assertEquals("[ko_en]저는 커피를 마십니다.", r.intermediateText)
        assertEquals(listOf("argos", "argos", "opencc-script-guard"), r.steps.map { it.engine })
        assertEquals("[en_zh][ko_en]저는 커피를 마십니다.", r.translatedText)
    }

    @Test fun `Traditional output passes the s2tw script guard and reports changed characters`() {
        val r = service { _, _ -> "约这里" }.translate("About here.", Lang.EN, Lang.ZH_HANT)
        assertEquals("約這里", r.translatedText)
        val guard = r.steps.last()
        assertEquals("opencc-script-guard", guard.engine)
        assertEquals("s2tw.json", guard.config)
        assertEquals(2, guard.changedChars)
    }

    @Test fun `Chinese output is joined without spaces and punctuation normalised`() {
        val r = service { _, s -> if (s.startsWith("A")) "你好," else "世界." }.translate("A b. C d.", Lang.EN, Lang.ZH_HANS)
        assertEquals("你好，世界。", r.translatedText)
    }

    @Test fun `script conversion uses OpenCC only`() {
        val r = service().translate("这个约定", Lang.ZH_HANS, Lang.ZH_HANT)
        assertEquals("這个約定", r.translatedText)
        assertEquals(listOf("opencc"), r.steps.map { it.engine })
        assertTrue(models.isEmpty())
    }

    @Test fun `missing model fails before any inference and never echoes the source`() {
        val e = assertThrows(EngineException::class.java) { service(missing = setOf("en_zh")).translate("안녕", Lang.KO, Lang.ZH_HANS) }
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, e.code)
        assertTrue(models.isEmpty())
    }

    @Test fun `empty model output is an error, empty input is not`() {
        assertEquals(ErrorCode.TRANSLATION_FAILED, assertThrows(EngineException::class.java) { service { _, _ -> "" }.translate("Hello.", Lang.EN, Lang.KO) }.code)
        assertEquals("  \n ", service().translate("  \n ", Lang.EN, Lang.KO).translatedText)
        val same = service().translate("Hello", Lang.EN, Lang.EN)
        assertEquals("Hello", same.translatedText)
        assertTrue(same.steps.isEmpty())
        assertNull(same.intermediateText)
    }

    @Test fun `cancellation stops between steps`() {
        val e = assertThrows(EngineException::class.java) {
            service().translate("Hi", Lang.EN, Lang.KO, CancelCheck { throw EngineException(ErrorCode.CANCELLED, "cancelled") })
        }
        assertEquals(ErrorCode.CANCELLED, e.code)
    }

    @Test fun `result map has the shape the UI validates`() {
        val m = service().translate("Hi", Lang.KO, Lang.ZH_HANT).toMap()
        assertEquals(setOf("translatedText", "sourceLanguage", "targetLanguage", "route", "pivoted", "modelsUsed", "steps", "elapsedMs", "intermediateText"), m.keys)
        assertEquals(listOf("ko", "en", "zh-Hant"), m["route"])
    }
}
