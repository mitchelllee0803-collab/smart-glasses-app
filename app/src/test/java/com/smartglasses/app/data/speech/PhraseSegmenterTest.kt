package com.smartglasses.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseSegmenterTest {
    private val blockMs = AudioInput.BLOCK_MS
    private fun block(level: Float) = FloatArray(AudioInput.BLOCK_SAMPLES) { if (it % 2 == 0) level else -level }
    private val quiet = block(0.001f)
    private val loud = block(0.2f)

    private fun PhraseSegmenter.feed(b: FloatArray) = push(b, rms(b), blockMs)

    @Test
    fun `silence never starts a phrase`() {
        val seg = PhraseSegmenter()
        repeat(50) { assertNull(seg.feed(quiet)) }
        assertFalse(seg.inPhrase)
    }

    @Test
    fun `speech then a pause gives partials and one final with preroll`() {
        val seg = PhraseSegmenter()
        repeat(10) { seg.feed(quiet) }
        val events = mutableListOf<SegmenterEvent?>()
        repeat(15) { events += seg.feed(loud) } // 1.5 s of speech
        assertTrue(SegmenterEvent.PARTIAL in events)
        assertTrue(seg.inPhrase)
        assertEquals(1_500, seg.phraseSpeechMs)

        var final: SegmenterEvent? = null
        var pauseBlocks = 0
        while (final != SegmenterEvent.FINAL) {
            final = seg.feed(quiet)
            pauseBlocks++
        }
        assertEquals(SegmenterOptions().pauseMs / blockMs, pauseBlocks)
        val audio = seg.takePhrase()
        // 300 ms preroll + speech + pause.
        assertEquals((3 + 15 + pauseBlocks) * AudioInput.BLOCK_SAMPLES, audio.size)
        assertFalse(seg.inPhrase)
    }

    @Test
    fun `long speech is cut at the maximum phrase length`() {
        val seg = PhraseSegmenter()
        var blocks = 0
        var event: SegmenterEvent?
        do {
            event = seg.feed(loud)
            blocks++
        } while (event != SegmenterEvent.FINAL)
        assertEquals(SegmenterOptions().maxPhraseMs / blockMs, blocks)
    }

    @Test
    fun `pcm16 is little endian and clipped`() {
        val pcm = toPcm16(floatArrayOf(0f, 1f, -1f, 2f))
        assertEquals(listOf(0, 0, 0xff, 0x7f, 0x01, 0x80, 0xff, 0x7f), pcm.map { it.toInt() and 0xff })
    }
}
