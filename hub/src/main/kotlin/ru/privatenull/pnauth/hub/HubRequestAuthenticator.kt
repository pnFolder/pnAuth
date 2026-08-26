package ru.privatenull.pnauth.hub

import com.sun.net.httpserver.Headers
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HubRequestAuthenticator(
    private val clients: Map<String, String>,
    private val toleranceSeconds: Int,
    private val clock: Clock = Clock.systemUTC()
) {
    private val nonces = ConcurrentHashMap<String, Long>()

    fun authenticate(method: String, path: String, headers: Headers, body: ByteArray): String? {
        val clientId = headers.getFirst("X-PnAuth-Client") ?: return null
        val timestamp = headers.getFirst("X-PnAuth-Timestamp")?.toLongOrNull() ?: return null
        val nonce = headers.getFirst("X-PnAuth-Nonce") ?: return null
        val signature = headers.getFirst("X-PnAuth-Signature") ?: return null
        val secret = clients[clientId] ?: return null
        if (!nonce.matches(Regex("[A-Za-z0-9_-]{16,128}"))) return null
        val now = clock.instant().epochSecond
        if (kotlin.math.abs(now - timestamp) > toleranceSeconds) return null
        cleanup(now)
        val digest = MessageDigest.getInstance("SHA-256").digest(body).toHex()
        val canonical = "$method\n$path\n$timestamp\n$nonce\n$digest"
        val expected = hmac(secret, canonical)
        if (!MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())) return null
        return if (nonces.putIfAbsent("$clientId:$nonce", now + toleranceSeconds) == null) clientId else null
    }

    private fun cleanup(now: Long) {
        if (nonces.size > 10_000) nonces.entries.removeIf { it.value < now }
    }

    companion object {
        fun hmac(secret: String, canonical: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
