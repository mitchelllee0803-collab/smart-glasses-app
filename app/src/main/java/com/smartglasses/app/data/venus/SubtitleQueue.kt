package com.smartglasses.app.data.venus

import com.smartglasses.app.domain.SubtitleUpdate
import java.util.TreeMap
import java.util.TreeSet

/**
 * Subtitle messages waiting for the glasses. Bluetooth is slower than speech recognition, so
 * only the newest update per line is kept (a partial superseded before it was sent is never
 * sent), and lines go out oldest first. Decides NEW / UPDATE / REMOVE per line.
 * Not thread-safe: used from one confined coroutine.
 */
class SubtitleQueue {
    sealed interface Outgoing {
        val lineId: Long

        data class Send(val update: SubtitleUpdate, val isNew: Boolean) : Outgoing {
            override val lineId: Long get() = update.lineId
        }

        data class Remove(override val lineId: Long) : Outgoing
    }

    private val updates = TreeMap<Long, SubtitleUpdate>()
    private val removals = TreeSet<Long>()
    /** Lines the glasses have (a NEW was delivered and not removed since). */
    private val created = HashSet<Long>()

    val isEmpty: Boolean get() = updates.isEmpty() && removals.isEmpty()

    fun offer(update: SubtitleUpdate) {
        removals.remove(update.lineId)
        updates[update.lineId] = update
    }

    fun remove(lineId: Long) {
        updates.remove(lineId)
        if (lineId in created) removals.add(lineId)
    }

    /** The next message, oldest line first; it is removed from the queue. */
    fun poll(): Outgoing? {
        val updateId = updates.firstEntry()?.key
        val removalId = removals.firstOrNull()
        return when {
            removalId != null && (updateId == null || removalId <= updateId) -> {
                removals.remove(removalId)
                Outgoing.Remove(removalId)
            }
            updateId != null -> {
                val update = updates.remove(updateId)!!
                Outgoing.Send(update, isNew = updateId !in created)
            }
            else -> null
        }
    }

    /**
     * Records the delivery result. A failed NEW leaves the line uncreated so its next update is
     * sent as NEW again. A timeout counts as delivered: the glasses may have shown it, and a
     * second NEW would duplicate the line.
     */
    fun onResult(message: Outgoing, delivered: Boolean) {
        when (message) {
            is Outgoing.Send -> if (message.isNew && delivered) created.add(message.lineId)
            is Outgoing.Remove -> created.remove(message.lineId)
        }
    }

    /** A new subtitle view on the glasses: nothing is shown there yet. */
    fun reset() {
        updates.clear()
        removals.clear()
        created.clear()
    }
}
