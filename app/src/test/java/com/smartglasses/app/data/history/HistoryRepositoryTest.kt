package com.smartglasses.app.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryRepositoryTest {
    @Test
    fun `blank search lists everything`() {
        assertNull(HistoryRepository.likePattern("   "))
    }

    @Test
    fun `search text is matched literally`() {
        assertEquals("%hello%", HistoryRepository.likePattern(" hello "))
        assertEquals("%100\\% sure\\_x\\\\y%", HistoryRepository.likePattern("100% sure_x\\y"))
    }
}
