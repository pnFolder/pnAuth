package ru.privatenull.pnauth.security;

import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.storage.PasswordHash;
import ru.privatenull.pnauth.config.AuthSettings;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void supportsConfiguredPasswordAlgorithms() {
        for (HashAlgorithm algorithm : HashAlgorithm.values()) {
            AuthSettings settings = new AuthSettings(
                    4, 64, 5, Duration.ofMinutes(1), 10_000,
                    "^[A-Za-z0-9_]{3,16}$", algorithm, 4, 1, 8_192, 1
            );
            PasswordHash hash = PasswordHasher.hash("correct-password", settings);
            assertTrue(PasswordHasher.matches("correct-password", hash), algorithm.name());
            assertFalse(PasswordHasher.matches("wrong-password", hash), algorithm.name());
            if (algorithm == HashAlgorithm.ARGON2) {
                assertTrue(hash.hash().startsWith("$argon2id$"));
            }
        }
    }

    @Test
    void rejectsMalformedStoredHashesWithoutThrowing() {
        assertFalse(PasswordHasher.matches("password", new PasswordHash("PBKDF2", "not-base64", "also-not-base64", 1)));
        assertFalse(PasswordHasher.matches("password", new PasswordHash("UNKNOWN", "", "hash", 1)));
    }

    @Test
    void marksLegacyHashesForUpgradeAfterTheyAreVerified() {
        AuthSettings settings = new AuthSettings(4, 64, 5, Duration.ofMinutes(1), 10_000);
        assertTrue(PasswordHasher.needsRehash(PasswordHash.legacy("SHA256", "hash"), settings));
        assertFalse(PasswordHasher.needsRehash(PasswordHasher.hash("password", settings), settings));
    }
}
