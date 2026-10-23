package com.smartglasses.app.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordTest {
    @Test
    fun `a stored hash accepts only the right password`() {
        val stored = PasswordHasher.hash("123123")
        assertTrue(PasswordHasher.verify("123123", stored))
        assertFalse(PasswordHasher.verify("123124", stored))
        assertFalse(PasswordHasher.verify("", stored))
    }

    @Test
    fun `the same password gets a different salt and hash each time`() {
        val a = PasswordHasher.hash("secret")
        val b = PasswordHasher.hash("secret")
        assertNotEquals(a, b)
        assertTrue(PasswordHasher.verify("secret", b))
    }

    @Test
    fun `five wrong passwords start a 30 second wait`() {
        var now = 0L
        val limiter = AttemptLimiter { now }
        repeat(AttemptLimiter.MAX_ATTEMPTS - 1) { i ->
            assertEquals(AttemptLimiter.MAX_ATTEMPTS - 1 - i, limiter.onFailure())
        }
        assertEquals(0, limiter.onFailure())
        assertEquals(30, limiter.secondsBlocked())
        now += 10_000
        assertEquals(20, limiter.secondsBlocked())
        now += 20_000
        assertEquals(0, limiter.secondsBlocked())
        // After the wait, a full set of attempts again.
        assertEquals(AttemptLimiter.MAX_ATTEMPTS - 1, limiter.onFailure())
    }

    @Test
    fun `a correct password resets the count`() {
        val limiter = AttemptLimiter { 0L }
        limiter.onFailure()
        limiter.onFailure()
        limiter.onSuccess()
        assertEquals(AttemptLimiter.MAX_ATTEMPTS - 1, limiter.onFailure())
    }
}
