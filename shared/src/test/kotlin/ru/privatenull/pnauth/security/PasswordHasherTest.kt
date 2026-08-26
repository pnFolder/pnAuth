package ru.privatenull.pnauth.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.config.AuthSettings
import ru.privatenull.pnauth.storage.PasswordHash
import java.time.Duration

class PasswordHasherTest {
    @Test
    fun supportsConfiguredPasswordAlgorithms() {
        for (algorithm in HashAlgorithm.entries) {
            val settings = AuthSettings(
                4, 64, 5, Duration.ofMinutes(1), 10_000,
                "^[A-Za-z0-9_]{3,16}$", algorithm, 4, 1, 8_192, 1
            )
            val hash = PasswordHasher.hash("correct-password", settings)
            assertTrue(PasswordHasher.matches("correct-password", hash), algorithm.name)
            assertFalse(PasswordHasher.matches("wrong-password", hash), algorithm.name)
            if (algorithm == HashAlgorithm.ARGON2) {
                assertTrue(hash.hash.startsWith("\$argon2id\$"))
            }
        }
    }

    @Test
    fun rejectsMalformedStoredHashesWithoutThrowing() {
        assertFalse(PasswordHasher.matches("password", PasswordHash("PBKDF2", "not-base64", "also-not-base64", 1)))
        assertFalse(PasswordHasher.matches("password", PasswordHash("UNKNOWN", "", "hash", 1)))
    }

    @Test
    fun marksLegacyHashesForUpgradeAfterTheyAreVerified() {
        val settings = AuthSettings(4, 64, 5, Duration.ofMinutes(1), 10_000)
        assertTrue(PasswordHasher.needsRehash(PasswordHash.legacy("SHA256", "hash"), settings))
        assertFalse(PasswordHasher.needsRehash(PasswordHasher.hash("password", settings), settings))
    }
}
