package com.smartglasses.app.data.translation

import com.smartglasses.app.domain.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtPromptTest {
    private val prefix = "<｜hy_begin▁of▁sentence｜><｜hy_User｜>"
    private val suffix = "<｜hy_Assistant｜>"

    @Test
    fun `English prompt from the model card when no Chinese is involved`() {
        val prompt = HyMtPrompt.build("Hello there.", Language.ENGLISH, Language.KOREAN, emptyList())
        assertEquals(
            prefix + "Translate the following text into Korean. Note that you should only output the translated " +
                "result without any additional explanation:\n\nHello there." + suffix,
            prompt,
        )
    }

    @Test
    fun `Chinese prompt with Chinese language names when Chinese is involved`() {
        val prompt = HyMtPrompt.build("안녕하세요.", Language.KOREAN, Language.CHINESE_TRADITIONAL, emptyList())
        assertEquals(prefix + "将以下文本翻译为繁体中文，注意只需要输出翻译后的结果，不要额外解释：\n\n안녕하세요." + suffix, prompt)
    }

    @Test
    fun `earlier sentences become background information`() {
        val prompt = HyMtPrompt.build("It is mine.", Language.ENGLISH, Language.KOREAN, listOf("Where is the bag?", "The red one."))
        assertEquals(
            prefix + "[Background Information]\nWhere is the bag?\nThe red one.\n\n" +
                "Please translate the following text into Korean, taking the provided background information into consideration." +
                "\n\n[Source Text]\nIt is mine." + suffix,
            prompt,
        )
        val zh = HyMtPrompt.build("是我的。", Language.CHINESE_SIMPLIFIED, Language.ENGLISH, listOf("包在哪里？"))
        assertEquals(prefix + "【背景信息】\n包在哪里？\n\n请结合背景信息将以下文本翻译为英语。\n\n【待翻译文本】\n是我的。" + suffix, zh)
    }

    @Test
    fun `background keeps the newest sentences within the limit`() {
        val old = "a".repeat(HyMtPrompt.MAX_CONTEXT_CHARS)
        val prompt = HyMtPrompt.build("x", Language.ENGLISH, Language.KOREAN, listOf(old, "newest"))
        assertTrue(prompt.contains("newest"))
        assertFalse(prompt.contains(old))
        // Only blank context: default template.
        assertTrue(HyMtPrompt.build("x", Language.ENGLISH, Language.KOREAN, listOf(" ")).contains("Translate the following text"))
    }

    @Test
    fun `generation budget grows with the text within bounds`() {
        assertEquals(48, HyMtPrompt.maxNewTokens("hi"))
        assertEquals(232, HyMtPrompt.maxNewTokens("x".repeat(100)))
        assertEquals(384, HyMtPrompt.maxNewTokens("x".repeat(5000)))
    }
}
