package ru.privatenull.pnauth.config

import ru.privatenull.pnauth.routing.ServerBalancerMode

data class ProxySettings @JvmOverloads constructor(
    val requireServerAuth: Boolean,
    val authServer: String,
    val backendServer: String = "hub",
    val forcedHosts: Map<String, String> = emptyMap(),
    val backendServers: List<String> = if (backendServer.isNotBlank()) listOf(backendServer) else emptyList(),
    val authServers: List<String> = if (authServer.isNotBlank()) listOf(authServer) else emptyList(),
    val balancerMode: ServerBalancerMode = ServerBalancerMode.LEAST_PLAYERS,
    val maxPlayersPerServer: Int = 100,
    val serverLimits: Map<String, Int> = emptyMap()
) {
    init {
        require(authServer.isNotBlank() || authServers.isNotEmpty()) { "authServer or authServers must not be blank" }
        val trimmedBackend = backendServer.trim()
        if (trimmedBackend.isNotEmpty() && authServer.equals(trimmedBackend, ignoreCase = true)) {
            throw IllegalArgumentException("authServer and backendServer must differ")
        }
        if (forcedHosts.entries.any { it.key.isBlank() || it.value.isBlank() }) {
            throw IllegalArgumentException("forcedHosts must not contain blank hostnames or server names")
        }
    }

    fun getEffectiveBackendServers(): List<String> {
        if (backendServers.isNotEmpty()) return backendServers
        return if (backendServer.isBlank()) emptyList() else listOf(backendServer)
    }

    fun getEffectiveAuthServers(): List<String> {
        if (authServers.isNotEmpty()) return authServers
        return listOf(authServer)
    }

    fun hasBackendServer(): Boolean = getEffectiveBackendServers().isNotEmpty()

    fun requiringServerAuth(): ProxySettings {
        return if (requireServerAuth) this else copy(requireServerAuth = true)
    }

    companion object {
        @JvmStatic
        fun defaults(): ProxySettings = ProxySettings(true, "auth", "hub", emptyMap())
    }
}
