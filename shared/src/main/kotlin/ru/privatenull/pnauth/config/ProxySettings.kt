package ru.privatenull.pnauth.config

import ru.privatenull.pnauth.routing.ServerBalancerMode

/**
 * Proxy routing configuration.
 *
 * The persistent YAML exposes only server groups. [authServer] and [backendServer]
 * are compatibility accessors for code paths that need the first configured target.
 */
data class ProxySettings @JvmOverloads constructor(
    val requireServerAuth: Boolean,
    val authServers: List<String>,
    val backendServers: List<String> = listOf("hub"),
    val forcedHosts: Map<String, String> = emptyMap(),
    val balancerMode: ServerBalancerMode = ServerBalancerMode.LEAST_PLAYERS,
    val maxPlayersPerServer: Int = 100,
    val serverLimits: Map<String, Int> = emptyMap()
) {
    /** Compatibility constructor for code written against the pre-v13 single-server model. */
    constructor(
        requireServerAuth: Boolean,
        authServer: String,
        backendServer: String = "hub",
        forcedHosts: Map<String, String> = emptyMap(),
        backendServers: List<String> = if (backendServer.isNotBlank()) listOf(backendServer) else emptyList(),
        authServers: List<String> = if (authServer.isNotBlank()) listOf(authServer) else emptyList(),
        balancerMode: ServerBalancerMode = ServerBalancerMode.LEAST_PLAYERS,
        maxPlayersPerServer: Int = 100,
        serverLimits: Map<String, Int> = emptyMap()
    ) : this(
        requireServerAuth = requireServerAuth,
        authServers = if (authServers.isNotEmpty()) authServers else authServer.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
        backendServers = if (backendServers.isNotEmpty()) backendServers else backendServer.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
        forcedHosts = forcedHosts,
        balancerMode = balancerMode,
        maxPlayersPerServer = maxPlayersPerServer,
        serverLimits = serverLimits
    )

    init {
        validateGroup("servers.auth", authServers, required = true)
        validateGroup("servers.backend", backendServers, required = false)

        val authNames = authServers.map { it.lowercase() }.toSet()
        val overlap = backendServers.firstOrNull { it.lowercase() in authNames }
        if (overlap != null) {
            throw IllegalArgumentException(
                "Server '$overlap' is configured both as an auth server and a backend server. " +
                    "Keep authorization servers and lobby/backend servers in separate groups."
            )
        }
        if (forcedHosts.entries.any { it.key.isBlank() || it.value.isBlank() }) {
            throw IllegalArgumentException("servers.forced-hosts must not contain blank hostnames or server names")
        }
        if (maxPlayersPerServer < 1) {
            throw IllegalArgumentException("Server online limit must be greater than 0")
        }
        for ((server, limit) in serverLimits) {
            if (server.isBlank()) {
                throw IllegalArgumentException("Server online limits must not contain a blank server name")
            }
            if (limit < 1) {
                throw IllegalArgumentException("Server '$server' has invalid online limit $limit; expected a value greater than 0")
            }
        }
    }

    /** First configured auth target, retained for compatibility with older internal call sites. */
    val authServer: String
        get() = authServers.first()

    /** First configured backend target, or an empty string when post-auth transfer is disabled. */
    val backendServer: String
        get() = backendServers.firstOrNull().orEmpty()

    fun getEffectiveBackendServers(): List<String> = backendServers

    fun getEffectiveAuthServers(): List<String> = authServers

    fun hasBackendServer(): Boolean = backendServers.isNotEmpty()

    fun isAuthServer(serverName: String?): Boolean {
        if (serverName.isNullOrBlank()) return false
        return authServers.any { it.equals(serverName, ignoreCase = true) }
    }

    fun isBackendServer(serverName: String?): Boolean {
        if (serverName.isNullOrBlank()) return false
        return backendServers.any { it.equals(serverName, ignoreCase = true) }
    }

    fun requiringServerAuth(): ProxySettings {
        return if (requireServerAuth) this else copy(requireServerAuth = true)
    }

    companion object {
        @JvmStatic
        fun defaults(): ProxySettings = ProxySettings(
            requireServerAuth = true,
            authServers = listOf("auth"),
            backendServers = listOf("hub"),
            forcedHosts = emptyMap()
        )

        private fun validateGroup(path: String, servers: List<String>, required: Boolean) {
            if (required && servers.isEmpty()) {
                throw IllegalArgumentException(
                    "$path must contain at least one server. Example: - server: auth\n  online: 100"
                )
            }
            if (servers.any { it.isBlank() }) {
                throw IllegalArgumentException("$path contains an empty server name")
            }
            val duplicates = servers
                .groupBy { it.lowercase() }
                .filterValues { it.size > 1 }
                .keys
            if (duplicates.isNotEmpty()) {
                throw IllegalArgumentException("$path contains duplicate servers: ${duplicates.joinToString()}")
            }
        }
    }
}
