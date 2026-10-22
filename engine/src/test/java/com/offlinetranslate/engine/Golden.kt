package com.offlinetranslate.engine

import org.json.JSONArray
import org.json.JSONObject

/** Reference data produced by worker/scripts/make_android_golden.py from the Python worker. */
object Golden {
    private fun bytes(name: String): ByteArray =
        Golden::class.java.getResourceAsStream("/golden/$name")?.use { it.readBytes() }
            ?: error("missing test resource golden/$name (run worker/scripts/make_android_golden.py)")

    fun json(name: String) = JSONObject(String(bytes(name), Charsets.UTF_8))
    fun jsonArray(name: String) = JSONArray(String(bytes(name), Charsets.UTF_8))

    fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}

/** Character-level OpenCC tables captured from the real converter (enough for detection tests). */
class TableConverter(private val tables: Map<String, Map<String, String>>) : ChineseConverter {
    override fun convert(text: String, config: String): String {
        val t = tables[config] ?: return text
        return text.map { t[it.toString()] ?: it.toString() }.joinToString("")
    }
}
