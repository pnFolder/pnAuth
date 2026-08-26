package ru.privatenull.pnauth.security

import ru.privatenull.pnauth.storage.AuthRepository
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TotpService(
    private val repository: AuthRepository,
    encryptionKey: ByteArray?
) {
    private val encryptionKey: ByteArray

    init {
        require(!(encryptionKey == null || encryptionKey.size != 32)) {
            "TOTP encryption key must be 32 bytes"
        }
        this.encryptionKey = encryptionKey.clone()
    }

    fun generateSecret(): String {
        val bytes = ByteArray(20)
        RANDOM.nextBytes(bytes)
        return encodeBase32(bytes)
    }

    fun verify(secret: String?, code: String?): Boolean {
        if (secret == null || code == null || !code.matches(Regex("\\d{6}"))) {
            return false
        }
        val time = System.currentTimeMillis() / 30_000L
        for (offset in -1L..1L) {
            if (MessageDigest.isEqual(
                    generateCode(secret, time + offset).toByteArray(StandardCharsets.US_ASCII),
                    code.toByteArray(StandardCharsets.US_ASCII)
                )
            ) {
                return true
            }
        }
        return false
    }

    fun provisioningUri(issuer: String?, username: String, secret: String): String {
        val safeIssuer = if (issuer.isNullOrBlank()) "pnAuth" else issuer
        return "otpauth://totp/" + encode("$safeIssuer:$username") +
                "?secret=" + secret + "&issuer=" + encode(safeIssuer) + "&algorithm=SHA1&digits=6&period=30"
    }

    fun generateRecoveryCodes(amount: Int): List<String> {
        val codes = ArrayList<String>()
        for (i in 0 until amount) {
            val code = StringBuilder()
            for (part in 0 until 4) {
                if (part > 0) code.append('-')
                for (character in 0 until 4) {
                    code.append("ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[RANDOM.nextInt(32)])
                }
            }
            codes.add(code.toString())
        }
        return codes
    }

    fun saveRecoveryCodes(uniqueId: UUID, codes: List<String>) {
        repository.clearRecoveryCodes(uniqueId)
        codes.forEach { code -> repository.addRecoveryCode(uniqueId, hashRecoveryCode(code)) }
    }

    fun replaceTotpData(uniqueId: UUID, encryptedSecret: String, codes: List<String>) {
        repository.replaceTotpData(uniqueId, encryptedSecret, codes.map { hashRecoveryCode(it) })
    }

    fun clearTotpData(uniqueId: UUID) {
        repository.clearTotpData(uniqueId)
    }

    fun consumeRecoveryCode(uniqueId: UUID, code: String): Boolean {
        return repository.consumeRecoveryCode(uniqueId, hashRecoveryCode(code))
    }

    fun encrypt(secret: String): String {
        try {
            val iv = ByteArray(12)
            RANDOM.nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
            val result = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, result, 0, iv.size)
            System.arraycopy(encrypted, 0, result, iv.size, encrypted.size)
            return Base64.getEncoder().encodeToString(result)
        } catch (exception: Exception) {
            throw IllegalStateException("Could not encrypt TOTP secret", exception)
        }
    }

    fun decrypt(encrypted: String): String {
        try {
            val data = Base64.getDecoder().decode(encrypted)
            val iv = Arrays.copyOfRange(data, 0, 12)
            val payload = Arrays.copyOfRange(data, 12, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        } catch (exception: Exception) {
            throw IllegalStateException("Could not decrypt TOTP secret", exception)
        }
    }

    companion object {
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val RANDOM = SecureRandom()

        @JvmStatic
        fun hashRecoveryCode(code: String): String {
            try {
                return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                        .digest(code.replace("-", "").uppercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
                )
            } catch (exception: Exception) {
                throw IllegalStateException("Could not hash recovery code", exception)
            }
        }

        private fun generateCode(secret: String, counter: Long): String {
            var c = counter
            try {
                val key = decodeBase32(secret)
                val message = ByteArray(8)
                for (i in 7 downTo 0) {
                    message[i] = c.toByte()
                    c = c ushr 8
                }
                val mac = Mac.getInstance("HmacSHA1")
                mac.init(SecretKeySpec(key, "HmacSHA1"))
                val hash = mac.doFinal(message)
                val offset = hash[hash.size - 1].toInt() and 0x0f
                val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                        ((hash[offset + 1].toInt() and 0xff) shl 16) or
                        ((hash[offset + 2].toInt() and 0xff) shl 8) or
                        (hash[offset + 3].toInt() and 0xff)
                return String.format(Locale.ROOT, "%06d", binary % 1_000_000)
            } catch (exception: Exception) {
                throw IllegalStateException("Could not verify TOTP code", exception)
            }
        }

        private fun encodeBase32(bytes: ByteArray): String {
            val result = StringBuilder((bytes.size * 8 + 4) / 5)
            var buffer = 0
            var bits = 0
            for (value in bytes) {
                buffer = (buffer shl 8) or (value.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    result.append(BASE32[(buffer ushr bits) and 31])
                }
            }
            if (bits > 0) result.append(BASE32[(buffer shl (5 - bits)) and 31])
            return result.toString()
        }

        private fun decodeBase32(value: String): ByteArray {
            val normalized = value.replace("=", "").uppercase(Locale.ROOT)
            val result = ByteArray(normalized.length * 5 / 8)
            var buffer = 0
            var bits = 0
            var index = 0
            for (character in normalized.toCharArray()) {
                val valueIndex = BASE32.indexOf(character)
                require(valueIndex >= 0) { "Invalid Base32 secret" }
                buffer = (buffer shl 5) or valueIndex
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    result[index++] = ((buffer ushr bits) and 0xff).toByte()
                }
            }
            return result
        }

        private fun encode(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
    }
}
