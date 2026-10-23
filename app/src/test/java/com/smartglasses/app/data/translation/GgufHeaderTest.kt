package com.smartglasses.app.data.translation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GgufHeaderTest {
    /** Builds a little-endian GGUF header with the given metadata entries. */
    private class Writer {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun long(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun string(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            long(b.size.toLong())
            out.write(b)
        }
        fun header(kvCount: Long) {
            out.write("GGUF".toByteArray())
            int(3)
            long(354)
            long(kvCount)
        }
    }

    @Test
    fun `reads architecture and name, skipping other values`() {
        val w = Writer()
        w.header(5)
        w.string("general.type"); w.int(8); w.string("model")
        w.string("general.sampling.top_k"); w.int(5); w.int(20)
        w.string("some.array"); w.int(9); w.int(8); w.long(2); w.string("a"); w.string("bc")
        w.string("general.architecture"); w.int(8); w.string("hunyuan-dense")
        w.string("general.name"); w.int(8); w.string("1.8B")
        val info = GgufHeader.read(ByteArrayInputStream(w.out.toByteArray()))
        assertEquals(GgufInfo(3, "hunyuan-dense", "1.8B"), info)
    }

    @Test
    fun `stops at the tokenizer metadata`() {
        val w = Writer()
        w.header(3)
        w.string("general.architecture"); w.int(8); w.string("llama")
        w.string("tokenizer.ggml.model"); w.int(8); w.string("gpt2")
        // Anything after this point is never read (here: truncated data).
        val info = GgufHeader.read(ByteArrayInputStream(w.out.toByteArray()))
        assertEquals("llama", info.architecture)
        assertNull(info.name)
    }

    @Test(expected = IOException::class)
    fun `rejects files that are not GGUF`() {
        GgufHeader.read(ByteArrayInputStream("PK\u0003\u0004 not a model".toByteArray()))
    }
}
