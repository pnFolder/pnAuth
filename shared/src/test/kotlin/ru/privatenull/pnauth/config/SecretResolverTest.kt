package ru.privatenull.pnauth.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SecretResolverTest {
    @Test
    fun `resolves explicitly named environment value`() {
        assertEquals("secret", SecretResolver.resolve("${'$'}{ENV:PNAUTH_SECRET}", mapOf("PNAUTH_SECRET" to "secret")))
    }

    @Test
    fun `rejects missing environment value`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecretResolver.resolve("${'$'}{ENV:PNAUTH_SECRET}", emptyMap())
        }
    }

    @Test
    fun `leaves ordinary values unchanged`() {
        assertEquals("literal", SecretResolver.resolve("literal", emptyMap()))
    }
}
