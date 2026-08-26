package ru.privatenull.pnauth.config

import ru.privatenull.pnauth.routing.ServerBalancerMode

/** Runtime/config route target: proxy server name plus configured online capacity. */
class ServerTarget @JvmOverloads constructor(
    @JvmField var server: String = "",
    @JvmField var online: Int = 100
)

data class ProxySettings(
    val requireServerAuth: Boolean,
    val authServers: List<ServerTarget>,
    val backendServers: List<ServerTarget>,
    val forcedHosts: Map<String, String> = emptyMap(),
    val balancerMode: ServerBalancerMode = ServerBalancerMode.LEAST_PLAYERS
) {
    /** Compatibility constructor for extensions compiled against the previous routing model. */
    @JvmOverloads
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
        authServers = effectiveNames(authServer, authServers).map {
            ServerTarget(it, serverLimits[it] ?: maxPlayersPerServer)
        },
        backendServers = effectiveNames(backendServer, backendServers).map {
            ServerTarget(it, serverLimits[it] ?: maxPlayersPerServer)
        },
        forcedHosts = forcedHosts,
        balancerMode = balancerMode
    )

    init {
        if (requireServerAuth && authServers.isEmpty()) {
            throw IllegalArgumentException(
                "Authentication is required, but servers.auth-servers is empty. " +
                        "Add at least one auth route or disable servers.require-auth-before-server."
            )
        }
        if (authServers.any { it.server.isBlank() } || backendServers.any { it.server.isBlank() }) {
            throw IllegalArgumentException("servers.auth-servers/backend-servers must not contain blank server names")
        }
        if (authServers.any { it.online <= 0 } || backendServers.any { it.online <= 0 }) {
            throw IllegalArgumentException("Every server 'online' value must be greater than 0")
        }
        val authNames = authServers.map { it.server.lowercase() }.toSet()
        val conflict = backendServers.firstOrNull { it.server.lowercase() in authNames }
        if (conflict != null) {
            throw IllegalArgumentException(
                "Server '${conflict.server}' is configured in both servers.auth-servers and servers.backend-servers. " +
                        "A route must belong to only one group."
            )
        }
        if (forcedHosts.entries.any { it.key.isBlank() || it.value.isBlank() }) {
            throw IllegalArgumentException("servers.forced-hosts must not contain blank hostnames or server names")
        }
    }

    /** First auth target retained only as a compatibility convenience. */
    val authServer: String
        get() = authServers.firstOrNull()?.server.orEmpty()

    /** First backend target retained only as a compatibility convenience. */
    val backendServer: String
        get() = backendServers.firstOrNull()?.server.orEmpty()

    val maxPlayersPerServer: Int
        get() = 100

    val serverLimits: Map<String, Int>
        get() = (authServers + backendServers).associate { it.server to it.online }

    fun getEffectiveBackendServers(): List<String> = backendServers.map { it.server }

    fun getEffectiveAuthServers(): List<String> = authServers.map { it.server }

    fun hasBackendServer(): Boolean = backendServers.isNotEmpty()

    fun isAuthServer(serverName: String): Boolean =
        authServers.any { it.server.equals(serverName, ignoreCase = true) }

    fun requiringServerAuth(): ProxySettings =
        if (requireServerAuth) this else copy(requireServerAuth = true)

    companion object {
        @JvmStatic
        fun defaults(): ProxySettings = ProxySettings(
            true,
            listOf(ServerTarget("auth", 100)),
            listOf(ServerTarget("hub", 200)),
            emptyMap()
        )

        private fun effectiveNames(primary: String, values: List<String>): List<String> {
            val clean = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (clean.isNotEmpty()) return clean
            return primary.trim().takeIf { it.isNotEmpty() }?.let(::listOf) ?: emptyList()
        }
    }
}
