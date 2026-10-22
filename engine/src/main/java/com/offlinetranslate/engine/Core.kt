package com.offlinetranslate.engine

/*
 * Platform-independent engine core: a direct port of the Windows worker's Python logic
 * (worker/offline_translate_worker). No Android APIs here, so all of it is unit-tested on
 * the development PC (app/src/test). Native inference sits behind small interfaces.
 */

object ErrorCode {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val UNSUPPORTED_LANGUAGE = "UNSUPPORTED_LANGUAGE"
    const val INVALID_TEXT = "INVALID_TEXT"
    const val TEXT_TOO_LONG = "TEXT_TOO_LONG"
    const val MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
    const val RESOURCE_MISSING = "RESOURCE_MISSING"
    const val TRANSLATION_FAILED = "TRANSLATION_FAILED"
    const val INVALID_IMAGE = "INVALID_IMAGE"
    const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
    const val OCR_FAILED = "OCR_FAILED"
    const val INVALID_AUDIO = "INVALID_AUDIO"
    const val AUDIO_TOO_LONG = "AUDIO_TOO_LONG"
    const val NO_SPEECH = "NO_SPEECH"
    const val SPEECH_FAILED = "SPEECH_FAILED"
    const val CANCELLED = "CANCELLED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    // Android model import (ModelStore)
    const val MODELS_NOT_FOUND = "MODELS_NOT_FOUND"
    const val MODELS_DAMAGED = "MODELS_DAMAGED"
    const val INSUFFICIENT_STORAGE = "INSUFFICIENT_STORAGE"
    const val IMPORT_RUNNING = "IMPORT_RUNNING"
}

class EngineException(val code: String, message: String) : RuntimeException(message)

/** Cooperative cancellation, checked between units of work. */
fun interface CancelCheck {
    fun check()
}

val NO_CANCEL = CancelCheck { }

enum class Lang(val code: String, val argos: String, val tesseract: String, val chinese: Boolean) {
    EN("en", "en", "eng", false),
    KO("ko", "ko", "kor", false),
    ZH_HANS("zh-Hans", "zh", "chi_sim", true),
    ZH_HANT("zh-Hant", "zt", "chi_tra", true);

    companion object {
        fun of(code: Any?, field: String = "language"): Lang =
            entries.firstOrNull { it.code == code }
                ?: throw EngineException(
                    ErrorCode.UNSUPPORTED_LANGUAGE,
                    "Unsupported $field: $code. Supported: en, ko, zh-Hans, zh-Hant",
                )
    }
}

// ------------------------------------------------------------------- routing
sealed class Step(val source: Lang, val target: Lang) {
    class Argos(source: Lang, target: Lang) : Step(source, target) {
        val packageId: String get() = "${source.argos}_${target.argos}"
    }

    class OpenCC(source: Lang, target: Lang, val config: String) : Step(source, target)
}

class RoutePlan(val source: Lang, val target: Lang, val steps: List<Step>) {
    val pivoted: Boolean get() = steps.count { it is Step.Argos } > 1
    val route: List<Lang> get() = if (steps.isEmpty()) listOf(source) else listOf(steps[0].source) + steps.map { it.target }
    val requiredPackages: List<String> get() = steps.filterIsInstance<Step.Argos>().map { it.packageId }
}

fun planRoute(source: Lang, target: Lang): RoutePlan = when {
    source == target -> RoutePlan(source, target, emptyList())
    setOf(source, target) == setOf(Lang.ZH_HANS, Lang.ZH_HANT) ->
        RoutePlan(source, target, listOf(Step.OpenCC(source, target, if (source == Lang.ZH_HANS) "s2t.json" else "t2s.json")))
    source == Lang.EN || target == Lang.EN -> RoutePlan(source, target, listOf(Step.Argos(source, target)))
    // ko <-> Chinese: no direct package; two real translations via English.
    else -> RoutePlan(source, target, listOf(Step.Argos(source, Lang.EN), Step.Argos(Lang.EN, target)))
}

/**
 * Python's Unicode `\s` as an explicit character class. Android's regex engine (ICU) rejects the
 * `(?U)` flag, and plain `\s` means ASCII on the JVM but Unicode on Android, so neither is portable.
 */
const val UNICODE_SPACE = "[\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"

// --------------------------------------------------------- sentence splitting
object SentenceSplit {
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "inc", "ltd", "co", "corp",
        "no", "fig", "approx", "dept", "est", "mt", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
        "oct", "nov", "dec", "u.s", "u.k", "a.m", "p.m",
    )
    private const val CJK_TERMINATORS = "。！？!?；…"
    private const val CLOSERS = "\"'”’」』）)]】》"
    const val DEFAULT_MAX_SEGMENT_CHARS = 300

    /** Regex character-class body with every character escaped as \\uXXXX. */
    private fun cls(chars: String): String = chars.map { "\\u%04x".format(it.code) }.joinToString("")

    private val CJK_BREAK = Regex("([${cls(CJK_TERMINATORS)}]+[${cls(CLOSERS)}]*)")
    private val LATIN_BREAK = Regex("([.!?]+[${cls(CLOSERS)}]*)($UNICODE_SPACE+)")
    private val SOFT_BREAK = Regex("([,，、:：;；])")
    private val ABBREV_TAIL = Regex("(?:^|[^A-Za-z.])([A-Za-z][A-Za-z.]*)\\.$")

    private fun isAbbreviation(textBefore: String): Boolean {
        val m = ABBREV_TAIL.find(textBefore) ?: return false
        val token = m.groupValues[1]
        if (token.lowercase().trimEnd('.') in ABBREVIATIONS) return true
        return token.length == 1 && token[0].isUpperCase()
    }

    private fun splitLatin(text: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        for (m in LATIN_BREAK.findAll(text)) {
            val g1 = m.groups[1]!!
            val end = g1.range.last + 1
            val candidate = text.substring(start, end)
            if (g1.value.startsWith(".") && isAbbreviation(candidate)) continue
            parts.add(candidate)
            start = m.groups[2]!!.range.last + 1
        }
        parts.add(text.substring(start))
        return parts
    }

    private fun splitCjk(text: String): List<String> {
        val out = mutableListOf<String>()
        var last = 0
        for (m in CJK_BREAK.findAll(text)) {
            out.add(text.substring(last, m.range.last + 1))
            last = m.range.last + 1
        }
        if (last < text.length) out.add(text.substring(last))
        return out
    }

    private fun splitKeepingDelims(text: String, re: Regex): List<String> {
        val out = mutableListOf<String>()
        var last = 0
        for (m in re.findAll(text)) {
            out.add(text.substring(last, m.range.first))
            out.add(m.value)
            last = m.range.last + 1
        }
        out.add(text.substring(last))
        return out
    }

    private fun chunkLong(segment: String, maxChars: Int): List<String> {
        if (segment.length <= maxChars) return listOf(segment)
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (piece in splitKeepingDelims(segment, SOFT_BREAK)) {
            if (current.length + piece.length > maxChars && current.isNotEmpty()) {
                chunks.add(current.toString())
                current = StringBuilder()
            }
            current.append(piece)
            while (current.length > maxChars) {
                val s = current.toString()
                val space = s.lastIndexOf(' ', maxChars - 1) // Python: s.rfind(" ", 0, max_chars)
                val cut = if (space > maxChars / 2) space else maxChars
                chunks.add(s.substring(0, cut))
                current = StringBuilder(s.substring(cut))
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    /** Split one paragraph (no newlines) into trimmed, non-empty segments. */
    fun split(paragraph: String, maxChars: Int = DEFAULT_MAX_SEGMENT_CHARS): List<String> {
        val segments = mutableListOf<String>()
        for (latin in splitLatin(paragraph)) for (cjk in splitCjk(latin)) for (chunk in chunkLong(cjk, maxChars)) {
            val t = chunk.trim()
            if (t.isNotEmpty()) segments.add(t)
        }
        return segments
    }
}

// ------------------------------------------------------------- post-process
object PostProcess {
    private const val CJK = "㐀-䶿一-鿿豈-﫿"
    private val ASCII_AFTER_CJK = Regex("(?<=[$CJK])([,.?!:;])(?![0-9A-Za-z])")
    private val FULLWIDTH = mapOf(',' to "，", '.' to "。", '?' to "？", '!' to "！", ':' to "：", ';' to "；")
    private val ADDED_STOP = Regex("$UNICODE_SPACE*[.。]$")

    fun normalizeChinesePunctuation(text: String): String =
        ASCII_AFTER_CJK.replace(text) { FULLWIDTH.getValue(it.groupValues[1][0]) }

    /** True when the paragraph ends in a letter or digit (no closing punctuation, quote or bracket). */
    fun needsTerminalStop(paragraph: String): Boolean {
        val s = paragraph.trimEnd()
        if (s.isEmpty()) return false
        val cp = s.codePointBefore(s.length)
        return Character.isLetter(cp) || Character.getType(cp).let {
            it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() ||
                it == Character.OTHER_NUMBER.toInt()
        }
    }

    fun removeAddedStop(text: String): String = ADDED_STOP.replace(text, "")
}
