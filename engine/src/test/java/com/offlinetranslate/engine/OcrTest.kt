package com.offlinetranslate.engine

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTest {
    private fun lang(code: String) = Lang.of(code)

    @Test fun `hOCR from real Tesseract runs assembles to the same text as the Windows pipeline`() {
        val cases = Golden.jsonArray("ocr.json")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val label = "${c.getString("fixture")} psm ${c.getInt("psm")}"
            val l = lang(c.getString("language"))
            val lines = OcrText.groupWords(OcrText.parseHocr(c.getString("hocr")), l, 1.0)
            val expected: JSONArray = c.getJSONArray("lines")
            assertEquals("$label line count", expected.length(), lines.size)
            for (j in lines.indices) {
                val e = expected.getJSONObject(j)
                assertEquals("$label line $j", e.getString("text"), lines[j].text)
                val b = e.getJSONObject("bbox")
                assertEquals("$label bbox $j", BBox(b.getInt("x"), b.getInt("y"), b.getInt("width"), b.getInt("height")), lines[j].bbox)
                // hOCR confidences are integers, TSV ones have decimals.
                assertEquals("$label conf $j", e.getDouble("confidence"), lines[j].confidence, 1.0)
            }
            assertEquals(label, c.getString("text"), OcrText.assembleText(lines, l))
        }
    }

    @Test fun `Korean syllables are rejoined, word gaps kept`() {
        fun w(t: String, x: Int, width: Int = 20) = OcrWord(t, 90.0, BBox(x, 0, width, 30), 1, 1, 1)
        assertEquals("오늘은 날씨가", OcrText.joinWords(listOf(w("오", 0), w("늘", 24), w("은", 48), w("날씨가", 100, 60)), Lang.KO))
        assertEquals("这是测试。", OcrText.joinWords(listOf(w("这是", 0, 50), w("测试", 60, 50), w(".", 120, 5)), Lang.ZH_HANS))
        assertEquals("Hello world", OcrText.joinWords(listOf(w("Hello", 0, 50), w("world", 55, 50)), Lang.EN))
    }

    @Test fun `paragraphs and hyphenated wraps`() {
        fun ln(t: String, y: Int, block: Int = 1) = OcrLine(t, 90.0, BBox(0, y, 100, 20), block, 1, 1, 1)
        assertEquals("This is a long sentence that wraps.\nNew paragraph.",
            OcrText.assembleText(listOf(ln("This is a long sen-", 0), ln("tence that wraps.", 25), ln("New paragraph.", 80)), Lang.EN))
        assertEquals("第一行继续。\n新段落。", OcrText.assembleText(listOf(ln("第一行", 0), ln("继续。", 25), ln("新段落。", 45, block = 2)), Lang.ZH_HANS))
    }

    @Test fun `bounding boxes scale back with Python rounding`() {
        assertEquals(BBox(0, 0, 45, 10), BBox(0, 0, 90, 20).scaled(2.0))
        assertEquals(BBox(2, 2, 2, 4), BBox(5, 5, 5, 9).scaled(2.0)) // 2.5 -> 2, 4.5 -> 4 (half to even)
    }

    @Test fun `layout choice prefers page mode on ties`() {
        val a = listOf(OcrLine("abc", 80.0, BBox(0, 0, 1, 1), 1, 1, 1, 1))
        val b = listOf(OcrLine("abc", 82.0, BBox(0, 0, 1, 1), 1, 1, 1, 1))
        val c = listOf(OcrLine("abc", 90.0, BBox(0, 0, 1, 1), 1, 1, 1, 1))
        assertEquals(3, OcrText.chooseBest(listOf(3 to a, 6 to b)).first)
        assertEquals(6, OcrText.chooseBest(listOf(3 to a, 6 to c)).first)
    }

    @Test fun `grayscale and autocontrast match Pillow exactly`() {
        val p = Golden.json("preprocess.json")
        val w = p.getInt("width")
        val h = p.getInt("height")
        fun ints(key: String) = p.getJSONArray(key).let { a -> IntArray(a.length()) { a.getLong(it).toInt() } }
        val gray = Preprocess.grayscale(ints("argb"), w, h)
        assertArrayEquals(ints("gray"), gray.pixels)
        assertArrayEquals(ints("autocontrast"), Preprocess.autocontrast(gray).pixels)
    }
}
