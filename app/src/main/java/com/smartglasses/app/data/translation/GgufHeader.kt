package com.smartglasses.app.data.translation

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** The few GGUF header fields needed to check a model file before importing it. */
data class GgufInfo(val version: Int, val architecture: String?, val name: String?)

/**
 * Minimal GGUF header reader (format v2/v3: little endian, "GGUF" magic, then key/value metadata).
 * Stops at the tokenizer metadata, so only the start of the file is read.
 */
object GgufHeader {
    private const val MAGIC = 0x46554747 // "GGUF" read as little-endian int
    private const val MAX_STRING = 1 shl 20

    fun read(input: InputStream): GgufInfo {
        val reader = LeReader(DataInputStream(input.buffered()))
        if (reader.int() != MAGIC) throw IOException("Not a GGUF model file.")
        val version = reader.int()
        if (version !in 2..3) throw IOException("Unsupported GGUF version $version.")
        reader.long() // tensor count
        val kvCount = reader.long()
        var architecture: String? = null
        var name: String? = null
        for (i in 0 until kvCount) {
            val key = reader.string()
            if (key.startsWith("tokenizer.")) break
            val type = reader.int()
            when (key) {
                "general.architecture" -> architecture = reader.value(type) as? String
                "general.name" -> name = reader.value(type) as? String
                else -> reader.skip(type)
            }
            if (architecture != null && name != null) break
        }
        return GgufInfo(version, architecture, name)
    }

    private class LeReader(private val input: DataInputStream) {
        fun int(): Int = Integer.reverseBytes(input.readInt())
        fun long(): Long = java.lang.Long.reverseBytes(input.readLong())

        fun string(): String {
            val length = long()
            if (length !in 0..MAX_STRING) throw IOException("Corrupt GGUF header.")
            val bytes = ByteArray(length.toInt())
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        fun value(type: Int): Any? = when (type) {
            8 -> string()
            else -> {
                skip(type)
                null
            }
        }

        fun skip(type: Int) {
            when (type) {
                0, 1, 7 -> skipBytes(1)
                2, 3 -> skipBytes(2)
                4, 5, 6 -> skipBytes(4)
                10, 11, 12 -> skipBytes(8)
                8 -> skipBytes(long())
                9 -> {
                    val elementType = int()
                    val count = long()
                    for (i in 0 until count) skip(elementType)
                }
                else -> throw IOException("Corrupt GGUF header (type $type).")
            }
        }

        private fun skipBytes(count: Long) {
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw EOFException()
                    left--
                } else {
                    left -= skipped
                }
            }
        }
    }
}
