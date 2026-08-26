package ru.privatenull.pnauth.security

import at.favre.lib.crypto.bcrypt.BCrypt
import de.mkammerer.argon2.Argon2Factory
import ru.privatenull.pnauth.config.AuthSettings
import ru.privatenull.pnauth.storage.PasswordHash
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256
    private val RANDOM = SecureRandom()

    @JvmStatic
    fun hash(password: String, settings: AuthSettings): PasswordHash {
        return when (settings.hashAlgorithm()) {
            HashAlgorithm.BCRYPT -> hashBcrypt(password, settings.bcryptCost())
            HashAlgorithm.ARGON2 -> hashArgon2(password, settings)
            HashAlgorithm.PBKDF2 -> hashPbkdf2(password, settings.hashIterations())
        }
    }

    @JvmStatic
    fun matches(password: String?, expected: PasswordHash?): Boolean {
        if (password == null || expected == null || expected.algorithm.isBlank() || expected.hash.isBlank()) return false
        try {
            return when (expected.algorithm.uppercase(Locale.ROOT)) {
                "BCRYPT" -> verifyBcrypt(password, expected.hash)
                "ARGON2" -> verifyArgon2(password, expected.hash)
                "PBKDF2" -> matchesPbkdf2(password, expected)
                "SHA256" -> sha256Hex(password).equals(expected.hash, ignoreCase = true)
                "SHA256_AUTHME" -> verifyAuthMeSha(password, expected.hash)
                else -> false
            }
        } catch (exception: RuntimeException) {
            // A corrupt or legacy database entry must never crash the login worker.
            return false
        }
    }

    /**
     * Returns whether a successfully verified hash should be replaced with the configured
     * password scheme. This upgrades imported fast hashes without requiring every player
     * to reset a password.
     */
    @JvmStatic
    fun needsRehash(hash: PasswordHash?, settings: AuthSettings?): Boolean {
        if (hash == null || hash.algorithm.isBlank() || settings == null) return false
        val expectedAlgorithm = settings.hashAlgorithm().name
        if (!expectedAlgorithm.equals(hash.algorithm, ignoreCase = true)) return true
        return when (settings.hashAlgorithm()) {
            HashAlgorithm.PBKDF2 -> hash.iterations != settings.hashIterations()
            HashAlgorithm.BCRYPT -> hash.iterations != settings.bcryptCost()
            HashAlgorithm.ARGON2 -> hash.iterations != settings.argon2Iterations() || !hash.hash.startsWith("${'$'}argon2id${'$'}")
        }
    }

    private fun hashBcrypt(password: String, cost: Int): PasswordHash {
        val chars = password.toCharArray()
        try {
            return PasswordHash("BCRYPT", "", BCrypt.withDefaults().hashToString(cost, chars), cost)
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    private fun verifyBcrypt(password: String, hash: String): Boolean {
        val chars = password.toCharArray()
        try {
            return BCrypt.verifyer().verify(chars, hash).verified
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    private fun hashPbkdf2(password: String, iterations: Int): PasswordHash {
        val salt = ByteArray(SALT_BYTES)
        RANDOM.nextBytes(salt)
        return PasswordHash.pbkdf2(
            Base64.getEncoder().encodeToString(salt),
            Base64.getEncoder().encodeToString(derive(password, salt, iterations)),
            iterations
        )
    }

    private fun matchesPbkdf2(password: String, expected: PasswordHash): Boolean {
        val salt = Base64.getDecoder().decode(expected.salt)
        val actual = derive(password, salt, expected.iterations)
        val stored = Base64.getDecoder().decode(expected.hash)
        return MessageDigest.isEqual(actual, stored)
    }

    private fun hashArgon2(password: String, settings: AuthSettings): PasswordHash {
        val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        val chars = password.toCharArray()
        try {
            val hash = argon2.hash(
                settings.argon2Iterations(),
                settings.argon2Memory(),
                settings.argon2Parallelism(),
                chars
            )
            return PasswordHash.argon2(hash, settings.argon2Iterations())
        } finally {
            argon2.wipeArray(chars)
        }
    }

    private fun verifyArgon2(password: String, hash: String?): Boolean {
        val argon2 = Argon2Factory.create(
            if (hash != null && hash.startsWith("${'$'}argon2i${'$'}")) Argon2Factory.Argon2Types.ARGON2i else Argon2Factory.Argon2Types.ARGON2id
        )
        val chars = password.toCharArray()
        try {
            return argon2.verify(hash, chars)
        } finally {
            argon2.wipeArray(chars)
        }
    }

    private fun verifyAuthMeSha(password: String, hash: String): Boolean {
        if (hash.startsWith("${'$'}SHA${'$'}")) {
            val parts = hash.split("$")
            if (parts.size != 4) return false
            return parts[3].equals(hex(sha256(java.lang.String.valueOf(sha256(password)) + parts[2])), ignoreCase = true)
        }
        return hex(sha256(password)).equals(hash, ignoreCase = true)
    }

    private fun sha256(value: String): ByteArray {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        } catch (exception: GeneralSecurityException) {
            throw IllegalStateException(exception)
        }
    }

    private fun sha256Hex(value: String): String {
        return hex(sha256(value))
    }

    private fun hex(bytes: ByteArray): String {
        val result = StringBuilder(bytes.size * 2)
        for (value in bytes) result.append(String.format(Locale.ROOT, "%02x", value))
        return result.toString()
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val specification = PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification)
                .encoded
        } catch (exception: GeneralSecurityException) {
            throw IllegalStateException("PBKDF2 is unavailable", exception)
        } finally {
            specification.clearPassword()
        }
    }
}
