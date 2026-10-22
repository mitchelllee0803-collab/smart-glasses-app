package com.offlinetranslate.engine

import kotlin.math.max
import kotlin.math.min

/*
 * OCR post-processing, ported from worker/offline_translate_worker/ocr.py and preprocess.py.
 * Tesseract itself runs through Tesseract4Android; its hOCR output is parsed here.
 */

data class BBox(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun union(o: BBox): BBox {
        val x1 = min(x, o.x)
        val y1 = min(y, o.y)
        return BBox(x1, y1, max(x + w, o.x + o.w) - x1, max(y + h, o.y + o.h) - y1)
    }

    /** Back to original-image coordinates (Python round = half-to-even, like Math.rint). */
    fun scaled(factor: Double): BBox =
        if (factor == 1.0) this else BBox(rint(x / factor), rint(y / factor), rint(w / factor), rint(h / factor))

    fun toMap() = mapOf("x" to x, "y" to y, "width" to w, "height" to h)

    private fun rint(v: Double) = Math.rint(v).toInt()
}

data class OcrWord(val text: String, val conf: Double, val box: BBox, val block: Int, val paragraph: Int, val line: Int)

data class OcrLine(
    val text: String,
    val confidence: Double,
    val bbox: BBox,
    val block: Int,
    val paragraph: Int,
    val line: Int,
    val wordCount: Int,
) {
    fun toMap() = mapOf(
        "text" to text, "confidence" to round1(confidence), "bbox" to bbox.toMap(), "block" to block,
        "paragraph" to paragraph, "line" to line, "wordCount" to wordCount,
    )
}

fun round1(v: Double) = Math.rint(v * 10) / 10

fun median(values: List<Int>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

object OcrText {
    const val KOREAN_JOIN_GAP = 0.55
    const val PARAGRAPH_GAP = 0.9
    const val TARGET_LINE_HEIGHT = 36
    const val SMALL_LINE_HEIGHT = 22
    const val AUTO_TIE_MARGIN = 3.0

    private const val CJK = "　-〿㐀-䶿一-鿿豈-﫿＀-￯"
    private val SPACE_BETWEEN_CJK = Regex("(?<=[$CJK])$UNICODE_SPACE+(?=[$CJK])")
    private val SPACE_BEFORE_PUNCT = Regex("(?<=[$CJK])$UNICODE_SPACE+(?=[.,!?:;，。！？：；、])")
    private val HANGUL = Regex("[ᄀ-ᇿ㄰-㆏가-힯]")
    private val WS = Regex("$UNICODE_SPACE+")

    // --------------------------------------------------------------- hOCR
    private val TAG = Regex("""<(div|p|span)\b[^>]*\bclass=['"]([a-z_]+)['"][^>]*?\btitle=(['"])(.*?)\3[^>]*>""")
    private val BBOX = Regex("""bbox (\d+) (\d+) (\d+) (\d+)""")
    private val WCONF = Regex("""x_wconf (-?\d+(?:\.\d+)?)""")
    private val LINE_CLASSES = setOf("ocr_line", "ocr_textfloat", "ocr_header", "ocr_caption")

    private fun unescape(s: String): String = s.replace(Regex("<[^>]*>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace("&amp;", "&")

    /**
     * Words from Tesseract hOCR, numbered like Tesseract's TSV output: block per ocr_carea,
     * paragraph per ocr_par (reset per block), line per text line (reset per paragraph).
     */
    fun parseHocr(hocr: String): List<OcrWord> {
        val words = mutableListOf<OcrWord>()
        var block = 0
        var par = 0
        var line = 0
        for (m in TAG.findAll(hocr)) {
            val cls = m.groupValues[2]
            val title = m.groupValues[4]
            when {
                cls == "ocr_carea" -> { block++; par = 0; line = 0 }
                cls == "ocr_par" -> { par++; line = 0 }
                cls in LINE_CLASSES -> line++
                cls == "ocrx_word" -> {
                    val end = hocr.indexOf("</span>", m.range.last + 1)
                    if (end < 0) continue
                    val text = unescape(hocr.substring(m.range.last + 1, end)).trim()
                    val b = BBOX.find(title)?.groupValues ?: continue
                    val conf = WCONF.find(title)?.groupValues?.get(1)?.toDouble() ?: -1.0
                    if (text.isEmpty() || conf < 0) continue
                    val x0 = b[1].toInt()
                    val y0 = b[2].toInt()
                    words += OcrWord(text, conf, BBox(x0, y0, b[3].toInt() - x0, b[4].toInt() - y0), max(block, 1), max(par, 1), max(line, 1))
                }
            }
        }
        return words
    }

    // -------------------------------------------------------------- lines
    fun joinWords(words: List<OcrWord>, language: Lang): String {
        if (words.isEmpty()) return ""
        val height = median(words.map { it.box.h }).let { if (it == 0.0) 1.0 else it }
        val out = StringBuilder(words[0].text)
        for (i in 1 until words.size) {
            val prev = words[i - 1]
            val cur = words[i]
            val gap = cur.box.x - (prev.box.x + prev.box.w)
            val bothHangul = HANGUL.containsMatchIn(prev.text.takeLast(1)) && HANGUL.containsMatchIn(cur.text.take(1))
            if (language == Lang.KO && bothHangul && gap < KOREAN_JOIN_GAP * height) out.append(cur.text)
            else out.append(' ').append(cur.text)
        }
        var text = WS.replace(out, " ").trim()
        if (language.chinese) {
            text = SPACE_BETWEEN_CJK.replace(text, "")
            text = SPACE_BEFORE_PUNCT.replace(text, "")
            text = PostProcess.normalizeChinesePunctuation(text)
        }
        return text
    }

    fun groupWords(words: List<OcrWord>, language: Lang, scale: Double): List<OcrLine> {
        val groups = LinkedHashMap<Triple<Int, Int, Int>, MutableList<OcrWord>>()
        for (w in words) groups.getOrPut(Triple(w.block, w.paragraph, w.line)) { mutableListOf() }.add(w)
        val lines = mutableListOf<OcrLine>()
        for ((key, group) in groups) {
            val sorted = group.sortedBy { it.box.x }
            val text = joinWords(sorted, language)
            if (text.isEmpty()) continue
            var bbox = sorted[0].box
            for (w in sorted.drop(1)) bbox = bbox.union(w.box)
            val chars = sorted.sumOf { it.text.length }
            val conf = sorted.sumOf { it.conf * it.text.length } / max(1, chars)
            lines += OcrLine(text, conf, bbox.scaled(scale), key.first, key.second, key.third, sorted.size)
        }
        return lines
    }

    fun weightedConfidence(lines: List<OcrLine>): Double {
        val chars = lines.sumOf { it.text.length }
        return if (chars == 0) 0.0 else lines.sumOf { it.confidence * it.text.length } / chars
    }

    /** psm 3 (first) wins ties; another layout must be clearly more confident. */
    fun <T> chooseBest(candidates: List<Pair<T, List<OcrLine>>>): Pair<T, List<OcrLine>> {
        var best = candidates.first()
        for (c in candidates.drop(1)) {
            if (weightedConfidence(c.second) > weightedConfidence(best.second) + AUTO_TIE_MARGIN) best = c
        }
        return best
    }

    fun assembleText(lines: List<OcrLine>, language: Lang): String {
        if (lines.isEmpty()) return ""
        val joiner = if (language.chinese) "" else " "
        val medianH = median(lines.map { it.bbox.h }).let { if (it == 0.0) 1.0 else it }
        val paragraphs = mutableListOf(mutableListOf<String>())
        var prev: OcrLine? = null
        for (ln in lines) {
            if (prev != null) {
                val newBlock = ln.block != prev.block || ln.paragraph != prev.paragraph
                val gap = ln.bbox.y - (prev.bbox.y + prev.bbox.h)
                if (newBlock || gap > PARAGRAPH_GAP * medianH) paragraphs += mutableListOf<String>()
            }
            val buf = paragraphs.last()
            if (joiner.isNotEmpty() && buf.isNotEmpty() && buf.last().endsWith("-") && ln.text.take(1).let { it.isNotEmpty() && it[0].isLowerCase() }) {
                buf[buf.size - 1] = buf.last().dropLast(1) + ln.text
            } else {
                buf += ln.text
            }
            prev = ln
        }
        return paragraphs.filter { it.isNotEmpty() }.joinToString("\n") { it.joinToString(joiner) }
    }
}

/** Grayscale image as 0..255 values, row-major. */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray)

object Preprocess {
    /** Pillow's RGB -> L conversion (ITU-R 601-2 luma, integer rounding). */
    fun grayscale(argb: IntArray, width: Int, height: Int): GrayImage {
        val out = IntArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            val a = (p ushr 24) and 0xff
            var r = (p shr 16) and 0xff
            var g = (p shr 8) and 0xff
            var b = p and 0xff
            if (a < 255) { // transparency flattened on white, as on Windows
                r = (r * a + 255 * (255 - a)) / 255
                g = (g * a + 255 * (255 - a)) / 255
                b = (b * a + 255 * (255 - a)) / 255
            }
            out[i] = (r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16
        }
        return GrayImage(width, height, out)
    }

    /** Pillow ImageOps.autocontrast(image, cutoff=1). */
    fun autocontrast(img: GrayImage, cutoffPercent: Int = 1): GrayImage {
        val h = IntArray(256)
        for (v in img.pixels) h[v]++
        val n = img.pixels.size
        var cut = n * cutoffPercent / 100
        for (lo in 0 until 256) {
            if (cut > h[lo]) { cut -= h[lo]; h[lo] = 0 } else { h[lo] -= cut; cut = 0 }
            if (cut <= 0) break
        }
        cut = n * cutoffPercent / 100
        for (hi in 255 downTo 0) {
            if (cut > h[hi]) { cut -= h[hi]; h[hi] = 0 } else { h[hi] -= cut; cut = 0 }
            if (cut <= 0) break
        }
        val lo = (0 until 256).firstOrNull { h[it] != 0 } ?: return img
        val hi = (255 downTo 0).first { h[it] != 0 }
        if (hi <= lo) return img
        val scale = 255.0 / (hi - lo)
        val offset = -lo * scale
        val lut = IntArray(256) { ix -> (ix * scale + offset).toInt().coerceIn(0, 255) }
        return GrayImage(img.width, img.height, IntArray(n) { lut[img.pixels[it]] })
    }
}
