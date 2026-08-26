package ru.privatenull.pnauth.hub

import com.sun.net.httpserver.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HubRequestAuthenticatorTest {
    private val now = Instant.parse("2026-08-21T20:00:00Z")
    private val secret = "a".repeat(32)
    private val authenticator = HubRequestAuthenticator(mapOf("proxy-1" to secret), 30, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `accepts valid request once and rejects replay`() {
        val body = "{}".toByteArray()
        val headers = signed(body, "unique-nonce-1234")
        assertEquals("proxy-1", authenticator.authenticate("POST", "/api/v1/auth/verify", headers, body))
        assertNull(authenticator.authenticate("POST", "/api/v1/auth/verify", headers, body))
    }

    @Test
    fun `rejects changed body`() {
        val headers = signed("{}".toByteArray(), "unique-nonce-5678")
        assertNull(authenticator.authenticate("POST", "/api/v1/auth/verify", headers, "{x}".toByteArray()))
    }

    private fun signed(body: ByteArray, nonce: String): Headers {
        val timestamp = now.epochSecond.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val canonical = "POST\n/api/v1/auth/verify\n$timestamp\n$nonce\n$digest"
        return Headers().apply {
            set("X-PnAuth-Client", "proxy-1")
            set("X-PnAuth-Timestamp", timestamp)
            set("X-PnAuth-Nonce", nonce)
            set("X-PnAuth-Signature", HubRequestAuthenticator.hmac(secret, canonical))
        }
    }
}
