package com.smartglasses.app.data.translation

import com.smartglasses.app.domain.model.Language

/**
 * Prompts for Tencent Hy-MT2, following its model card: the "Default Translation" template, or the
 * "Background Information" template when earlier sentences give context. Chinese prompts (with
 * Chinese language names) when Chinese is involved, English prompts otherwise. The model has no
 * system prompt; its GGUF chat template wraps one user turn.
 */
object HyMtPrompt {
    private const val BEGIN = "<｜hy_begin▁of▁sentence｜>"
    private const val USER = "<｜hy_User｜>"
    private const val ASSISTANT = "<｜hy_Assistant｜>"

    /** At most this much earlier text is passed as background (characters). */
    const val MAX_CONTEXT_CHARS = 400

    fun build(text: String, source: Language, target: Language, context: List<String>): String {
        val chinese = source.isChinese || target.isChinese
        val targetName = if (chinese) target.chineseName else target.englishName
        val background = backgroundOf(context)
        val body = when {
            chinese && background == null ->
                "将以下文本翻译为${targetName}，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
            chinese ->
                "【背景信息】\n$background\n\n请结合背景信息将以下文本翻译为${targetName}。\n\n【待翻译文本】\n$text"
            background == null ->
                "Translate the following text into $targetName. Note that you should only output the translated result without any additional explanation:\n\n$text"
            else ->
                "[Background Information]\n$background\n\nPlease translate the following text into $targetName, taking the provided background information into consideration.\n\n[Source Text]\n$text"
        }
        return BEGIN + USER + body + ASSISTANT
    }

    /** Newest sentences that fit in [MAX_CONTEXT_CHARS], in speaking order; null when there are none. */
    private fun backgroundOf(context: List<String>): String? {
        val kept = ArrayDeque<String>()
        var chars = 0
        for (sentence in context.asReversed()) {
            val s = sentence.trim()
            if (s.isEmpty()) continue
            if (chars + s.length > MAX_CONTEXT_CHARS) break
            kept.addFirst(s)
            chars += s.length
        }
        return kept.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Generation budget: translations rarely need more than about twice the source length. */
    fun maxNewTokens(text: String): Int = (text.length * 2 + 32).coerceIn(48, 384)

    /** The model answers with the translation only; trim stray whitespace. */
    fun clean(output: String): String = output.trim()

    private val Language.isChinese: Boolean
        get() = this == Language.CHINESE_SIMPLIFIED || this == Language.CHINESE_TRADITIONAL

    private val Language.englishName: String
        get() = when (this) {
            Language.ENGLISH -> "English"
            Language.KOREAN -> "Korean"
            Language.CHINESE_SIMPLIFIED -> "Chinese"
            Language.CHINESE_TRADITIONAL -> "Traditional Chinese"
        }

    private val Language.chineseName: String
        get() = when (this) {
            Language.ENGLISH -> "英语"
            Language.KOREAN -> "韩语"
            Language.CHINESE_SIMPLIFIED -> "中文"
            Language.CHINESE_TRADITIONAL -> "繁体中文"
        }
}
