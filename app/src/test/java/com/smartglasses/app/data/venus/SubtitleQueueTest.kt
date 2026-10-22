package com.smartglasses.app.data.venus

import com.smartglasses.app.data.venus.SubtitleQueue.Outgoing
import com.smartglasses.app.domain.SubtitleUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleQueueTest {
    private fun update(id: Long, text: String, final: Boolean = false) = SubtitleUpdate(id, text, "t-$text", final)

    @Test
    fun `first message of a line is NEW, later ones UPDATE`() {
        val q = SubtitleQueue()
        q.offer(update(1, "a"))
        val first = q.poll() as Outgoing.Send
        assertTrue(first.isNew)
        q.onResult(first, delivered = true)
        q.offer(update(1, "ab"))
        val second = q.poll() as Outgoing.Send
        assertEquals(false, second.isNew)
        assertEquals("ab", second.update.original)
    }

    @Test
    fun `only the newest pending update per line is sent`() {
        val q = SubtitleQueue()
        q.offer(update(1, "a"))
        q.offer(update(1, "ab"))
        q.offer(update(1, "abc", final = true))
        val sent = q.poll() as Outgoing.Send
        assertEquals("abc", sent.update.original)
        assertTrue(sent.update.isFinal)
        assertNull(q.poll())
    }

    @Test
    fun `older lines go out first`() {
        val q = SubtitleQueue()
        q.offer(update(5, "later"))
        q.offer(update(3, "earlier", final = true))
        assertEquals(3L, q.poll()?.lineId)
        assertEquals(5L, q.poll()?.lineId)
    }

    @Test
    fun `a failed NEW is retried as NEW, a timed out one is not`() {
        val q = SubtitleQueue()
        q.offer(update(1, "a"))
        q.onResult(q.poll()!!, delivered = false)
        q.offer(update(1, "ab"))
        val retry = q.poll() as Outgoing.Send
        assertTrue(retry.isNew)
        q.onResult(retry, delivered = true) // timeout counts as delivered
        q.offer(update(1, "abc"))
        assertEquals(false, (q.poll() as Outgoing.Send).isNew)
    }

    @Test
    fun `remove is sent only for lines the glasses have`() {
        val q = SubtitleQueue()
        q.offer(update(1, "never sent"))
        q.remove(1)
        assertNull(q.poll())

        q.offer(update(2, "shown"))
        q.onResult(q.poll()!!, delivered = true)
        q.remove(2)
        assertEquals(Outgoing.Remove(2), q.poll())
    }

    @Test
    fun `reset forgets created lines`() {
        val q = SubtitleQueue()
        q.offer(update(1, "a"))
        q.onResult(q.poll()!!, delivered = true)
        q.reset()
        q.offer(update(1, "ab"))
        assertTrue((q.poll() as Outgoing.Send).isNew)
        assertTrue(q.isEmpty)
    }
}
