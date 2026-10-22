package com.offlinetranslate.engine

import kotlin.math.roundToLong

/** One Argos package's tokenizer + translator (native on Android, fakes in tests). */
interface SentenceTranslator {
    /** Translate sentences (one model call batch); returns detokenized outputs in order. */
    fun translateSentences(sentences: List<String>): List<String>
}

/** OpenCC conversion with a bundled config (s2t.json, t2s.json, s2tw.json). */
interface ChineseConverter {
    fun convert(text: String, config: String): String
    fun toTraditional(text: String) = convert(text, "s2t.json")
    fun toSimplified(text: String) = convert(text, "t2s.json")
    fun normalizeTraditional(text: String) = convert(text, "s2tw.json")
}

/** Which models are present (from the bundled manifest). */
interface ModelAvailability {
    /** Returns the package display name (e.g. "translate-en_ko-1_1") or throws MODEL_UNAVAILABLE. */
    fun requireArgos(packageId: String): String
    fun requireOpencc()
}

const val MAX_TEXT_CHARS = 5000

fun validateText(text: Any?, maxChars: Int = MAX_TEXT_CHARS): String {
    if (text !is String) throw EngineException(ErrorCode.INVALID_TEXT, "Text must be a string.")
    if ('\u0000' in text) throw EngineException(ErrorCode.INVALID_TEXT, "Text contains NUL characters.")
    // Lone surrogates cannot be encoded as UTF-8 for the models.
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) {
                throw EngineException(ErrorCode.INVALID_TEXT, "Text contains invalid Unicode (lone surrogates).")
            }
            i += 2
            continue
        }
        if (Character.isLowSurrogate(c)) throw EngineException(ErrorCode.INVALID_TEXT, "Text contains invalid Unicode (lone surrogates).")
        i++
    }
    val length = text.codePointCount(0, text.length)
    if (length > maxChars) throw EngineException(ErrorCode.TEXT_TOO_LONG, "Text is $length characters; the maximum is $maxChars.")
    return text.replace("\r\n", "\n").replace("\r", "\n")
}

/**
 * Paragraph handling for one Argos package, identical to the Windows worker's
 * ArgosPackageTranslator: per line; short unterminated input gets a sentence stop for the
 * model that is removed again; Chinese output joined without spaces and punctuation
 * normalised.
 */
class ParagraphTranslator(
    private val model: SentenceTranslator,
    private val sourceIsChinese: Boolean,
    private val targetIsChinese: Boolean,
) {
    fun translate(text: String, cancel: CancelCheck = NO_CANCEL): String =
        text.split("\n").joinToString("\n") { line ->
            cancel.check()
            translateParagraph(line)
        }

    private fun translateParagraph(original: String): String {
        if (original.isBlank()) return original
        var paragraph = original
        val terminated = PostProcess.needsTerminalStop(paragraph)
        if (terminated) paragraph = paragraph.trimEnd() + if (sourceIsChinese) "。" else "."
        val sentences = SentenceSplit.split(paragraph)
        if (sentences.isEmpty()) return ""
        val decoded = try {
            model.translateSentences(sentences).map { it.trim() }
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            throw EngineException(ErrorCode.TRANSLATION_FAILED, "Model inference failed: ${e.message}")
        }
        val leading = paragraph.substring(0, paragraph.length - paragraph.trimStart().length)
        var joined = decoded.filter { it.isNotEmpty() }.joinToString(if (targetIsChinese) "" else " ")
        if (targetIsChinese) joined = PostProcess.normalizeChinesePunctuation(joined)
        if (terminated) joined = PostProcess.removeAddedStop(joined)
        return leading + joined
    }
}

data class TranslationStep(
    val engine: String,
    val source: Lang,
    val target: Lang,
    val elapsedMs: Long,
    val outputChars: Int,
    val packageName: String? = null,
    val config: String? = null,
    val changedChars: Int? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("engine", engine)
        put("sourceLanguage", source.code)
        put("targetLanguage", target.code)
        put("elapsedMs", elapsedMs)
        put("outputChars", outputChars)
        packageName?.let { put("package", it) }
        config?.let { put("config", it) }
        changedChars?.let { put("changedChars", it) }
    }
}

data class TranslationOutput(
    val translatedText: String,
    val source: Lang,
    val target: Lang,
    val route: List<Lang>,
    val pivoted: Boolean,
    val elapsedMs: Long,
    val steps: List<TranslationStep>,
    val intermediateText: String?,
) {
    val modelsUsed: List<String> get() = steps.mapNotNull { it.packageName }

    fun toMap(): Map<String, Any?> = buildMap {
        put("translatedText", translatedText)
        put("sourceLanguage", source.code)
        put("targetLanguage", target.code)
        put("route", route.map { it.code })
        put("pivoted", pivoted)
        put("modelsUsed", modelsUsed)
        put("steps", steps.map { it.toMap() })
        put("elapsedMs", elapsedMs)
        intermediateText?.let { put("intermediateText", it) }
    }
}

/** s2tw (not s2t) for Traditional: s2t would rewrite correct model output such as 吃 -> 喫. */
val SCRIPT_GUARD = mapOf(Lang.ZH_HANT to "s2tw.json", Lang.ZH_HANS to "t2s.json")

class TranslationService(
    private val models: ModelAvailability,
    private val converter: ChineseConverter,
    /** Returns the (possibly cached) model for an Argos package id such as "en_ko". */
    private val sentenceTranslator: (packageId: String) -> SentenceTranslator,
    private val clock: () -> Long = { System.nanoTime() },
) {
    private fun ms(t0: Long) = ((clock() - t0) / 1e6).roundToLong()

    fun checkRouteAvailable(source: Lang, target: Lang) {
        for (step in planRoute(source, target).steps) {
            if (step is Step.Argos) models.requireArgos(step.packageId) else models.requireOpencc()
        }
    }

    fun translate(rawText: Any?, source: Lang, target: Lang, cancel: CancelCheck = NO_CANCEL): TranslationOutput {
        val text = validateText(rawText)
        val plan = planRoute(source, target)
        checkRouteAvailable(source, target) // fail fast before any inference
        val started = clock()
        var current = text
        val steps = mutableListOf<TranslationStep>()
        var intermediate: String? = null
        if (current.isNotBlank()) {
            for ((index, step) in plan.steps.withIndex()) {
                cancel.check()
                val t0 = clock()
                when (step) {
                    is Step.Argos -> {
                        val name = models.requireArgos(step.packageId)
                        val paragraph = ParagraphTranslator(sentenceTranslator(step.packageId), step.source.chinese, step.target.chinese)
                        current = paragraph.translate(current, cancel)
                        steps += TranslationStep("argos", step.source, step.target, ms(t0), current.length, packageName = name)
                        SCRIPT_GUARD[step.target]?.let { guard ->
                            // The en->zt model occasionally emits Simplified characters; force the requested script.
                            val t1 = clock()
                            val guarded = converter.convert(current, guard)
                            val changed = current.zip(guarded).count { (a, b) -> a != b }
                            steps += TranslationStep("opencc-script-guard", step.target, step.target, ms(t1), guarded.length, config = guard, changedChars = changed)
                            current = guarded
                        }
                    }
                    is Step.OpenCC -> {
                        current = converter.convert(current, step.config)
                        steps += TranslationStep("opencc", step.source, step.target, ms(t0), current.length, config = step.config)
                    }
                }
                if (plan.pivoted && index == 0) intermediate = current
                if (text.isNotBlank() && current.isBlank()) {
                    throw EngineException(ErrorCode.TRANSLATION_FAILED, "Step ${step.source.code}->${step.target.code} produced empty output.")
                }
            }
        }
        return TranslationOutput(current, source, target, plan.route, plan.pivoted, ms(started), steps, intermediate)
    }
}

// ---------------------------------------------------------------- detection
object Detect {
    private val HANGUL = Regex("[ᄀ-ᇿ㄰-㆏가-힯]")
    private val HAN = Regex("[㐀-䶿一-鿿豈-﫿]")
    private val LATIN = Regex("[A-Za-z]")

    private fun changed(a: String, b: String) = a.zip(b).count { (x, y) -> x != y } + kotlin.math.abs(a.length - b.length)

    fun detect(text: String, converter: ChineseConverter): Map<String, Any?> {
        val hangul = HANGUL.findAll(text).count()
        val hanChars = HAN.findAll(text).joinToString("") { it.value }
        val han = hanChars.length
        val latin = LATIN.findAll(text).count()
        val scores = linkedMapOf<String, Int>("hangul" to hangul, "han" to han, "latin" to latin)
        val weighted = linkedMapOf("ko" to hangul * 2.5, "zh" to han * 2.5, "en" to latin.toDouble())
        val total = weighted.values.sum()
        fun result(lang: String?, confidence: Double, ambiguous: Boolean, reason: String) = mapOf(
            "language" to lang, "confidence" to Math.round(confidence * 100) / 100.0, "ambiguous" to ambiguous,
            "reason" to reason, "scores" to scores,
        )
        if (total == 0.0) return result(null, 0.0, true, "No letters found.")
        val top = weighted.maxBy { it.value }.key // first max wins, like Python's max()
        val share = weighted.getValue(top) / total
        if (top == "ko") return result("ko", share, share < 0.6, "Hangul script.")
        if (top == "en") return result("en", share, share < 0.6, "Latin script.")
        val tradSpecific = changed(hanChars, converter.toSimplified(hanChars))
        val simpSpecific = changed(hanChars, converter.normalizeTraditional(hanChars))
        scores["traditionalSpecific"] = tradSpecific
        scores["simplifiedSpecific"] = simpSpecific
        val markers = tradSpecific + simpSpecific
        if (markers == 0) {
            return result("zh-Hans", share * 0.5, true, "Chinese, but every character is shared by Simplified and Traditional; please choose the script.")
        }
        val (lang, margin) = if (tradSpecific > simpSpecific) "zh-Hant" to tradSpecific.toDouble() / markers else "zh-Hans" to simpSpecific.toDouble() / markers
        return result(lang, share * margin, margin < 0.8 || markers < 2, "Chinese with $tradSpecific Traditional-specific and $simpSpecific Simplified-specific characters.")
    }
}
