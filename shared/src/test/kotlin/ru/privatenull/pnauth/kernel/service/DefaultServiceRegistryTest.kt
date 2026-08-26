package ru.privatenull.pnauth.kernel.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DefaultServiceRegistryTest {
    fun interface LinkService {
        fun linked(username: String): Boolean
    }

    @Test
    fun selectsHighestPriorityProviderAndFallsBackAfterUnregister() {
        val services = DefaultServiceRegistry()
        val key = ServiceKey.of("social", "discord-links", LinkService::class.java)
        val fallback = LinkService { false }
        val preferred = LinkService { true }
        services.register(key, "fallback-addon", 0, fallback)
        val registration = services.register(key, "discord-addon", 100, preferred)
        assertSame(preferred, services.find(key).orElseThrow())
        assertEquals(2, services.findAll(key).size)
        registration.close()
        assertSame(fallback, services.find(key).orElseThrow())
    }
}
