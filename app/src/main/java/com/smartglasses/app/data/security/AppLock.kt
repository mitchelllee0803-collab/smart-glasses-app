package com.smartglasses.app.data.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    /** Wrong password; [attemptsLeft] before a wait. */
    data class Wrong(val attemptsLeft: Int) : UnlockResult
    /** Too many wrong passwords: try again in [secondsLeft]. */
    data class Blocked(val secondsLeft: Int) : UnlockResult
}

/** Counts wrong passwords: after [MAX_ATTEMPTS] in a row, attempts wait [BLOCK_MS]. Pure (testable). */
class AttemptLimiter(private val now: () -> Long) {
    private var failures = 0
    private var blockedUntil = 0L

    fun secondsBlocked(): Int = ((blockedUntil - now() + 999) / 1000).toInt().coerceAtLeast(0)

    fun onSuccess() {
        failures = 0
        blockedUntil = 0
    }

    /** Returns the attempts left, or 0 when a wait has started. */
    fun onFailure(): Int {
        failures++
        if (failures >= MAX_ATTEMPTS) {
            failures = 0
            blockedUntil = now() + BLOCK_MS
            return 0
        }
        return MAX_ATTEMPTS - failures
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val BLOCK_MS = 30_000L
    }
}

/**
 * The password asked for when the app starts. Default "123321" until the user changes it; only a
 * salted hash is stored (app-private storage). Unlocked state lasts until the app process ends.
 */
@Singleton
class AppLock @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val limiter = AttemptLimiter(SystemClock::elapsedRealtime)
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    suspend fun unlock(password: String): UnlockResult = withContext(Dispatchers.Default) {
        limiter.secondsBlocked().takeIf { it > 0 }?.let { return@withContext UnlockResult.Blocked(it) }
        if (matches(password)) {
            limiter.onSuccess()
            _unlocked.value = true
            UnlockResult.Unlocked
        } else {
            val left = limiter.onFailure()
            if (left == 0) UnlockResult.Blocked(limiter.secondsBlocked()) else UnlockResult.Wrong(left)
        }
    }

    /** False when [current] is wrong. */
    suspend fun changePassword(current: String, new: String): Boolean = withContext(Dispatchers.Default) {
        if (!matches(current)) return@withContext false
        val hash = PasswordHasher.hash(new)
        prefs.edit()
            .putString(KEY_SALT, hash.salt.encode())
            .putString(KEY_HASH, hash.hash.encode())
            .putInt(KEY_ITERATIONS, hash.iterations)
            .apply()
        true
    }

    private fun matches(password: String): Boolean {
        val stored = stored() ?: return password == DEFAULT_PASSWORD
        return PasswordHasher.verify(password, stored)
    }

    private fun stored(): PasswordHash? {
        val salt = prefs.getString(KEY_SALT, null) ?: return null
        val hash = prefs.getString(KEY_HASH, null) ?: return null
        return PasswordHash(salt.decode(), hash.decode(), prefs.getInt(KEY_ITERATIONS, PasswordHasher.ITERATIONS))
    }

    private fun ByteArray.encode() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decode() = Base64.decode(this, Base64.NO_WRAP)

    companion object {
        const val DEFAULT_PASSWORD = "123321"
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 32
        private const val PREFS = "security"
        private const val KEY_SALT = "password_salt"
        private const val KEY_HASH = "password_hash"
        private const val KEY_ITERATIONS = "password_iterations"
    }
}
