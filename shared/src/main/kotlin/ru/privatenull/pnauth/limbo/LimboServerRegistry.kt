package ru.privatenull.pnauth.limbo

import java.util.LinkedHashMap
import java.util.Locale

class LimboServerRegistry {
    private val providers = LinkedHashMap<String, LimboServerProvider>()

    fun register(provider: LimboServerProvider) {
        require(provider.id().isNotBlank()) { "Invalid limbo provider" }
        val key = provider.id().lowercase(Locale.ROOT)
        require(!providers.containsKey(key)) { "Limbo provider is already registered: ${provider.id()}" }
        providers[key] = provider
    }

    fun create(providerId: String, context: LimboServerContext): LimboServer {
        val provider = providers[providerId.lowercase(Locale.ROOT)]
            ?: throw IllegalArgumentException("Unknown limbo provider: $providerId")
        return provider.create(context)
    }
}
