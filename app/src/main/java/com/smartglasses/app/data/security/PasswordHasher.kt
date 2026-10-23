package com.smartglasses.app.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A stored password: salted PBKDF2 hash, never the password itself. */
data class PasswordHash(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {
    override fun equals(other: Any?) = other is PasswordHash && salt.contentEquals(other.salt) &&
        hash.contentEquals(other.hash) && iterations == other.iterations
    override fun hashCode() = 31 * (31 * salt.contentHashCode() + hash.contentHashCode()) + iterations
}

object PasswordHasher {
    /** PBKDF2WithHmacSHA1: the PBKDF2 variant available on every supported Android version (API 24+). */
    private const val ALGORITHM = "PBKDF2WithHmacSHA1"
    const val ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256

    fun hash(password: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): PasswordHash {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS)
        try {
            val hash = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            return PasswordHash(salt, hash, iterations)
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(password: String, stored: PasswordHash): Boolean =
        MessageDigest.isEqual(hash(password, stored.salt, stored.iterations).hash, stored.hash) // constant time

    private fun newSalt() = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
}
