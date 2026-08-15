package ru.privatenull.pnauth.config

@JvmRecord
data class ProxySettings(
    val requireServerAuth: Boolean,
    val authServer: String,
    val backendServer: String = "hub",
    val forcedHosts: Map<String, String> = emptyMap()
) {
    init {
        require(authServer.isNotBlank()) { "authServer must not be blank" }
        val trimmedBackend = backendServer.trim()
        if (trimmedBackend.isNotEmpty() && authServer.equals(trimmedBackend, ignoreCase = true)) {
            throw IllegalArgumentException("authServer and backendServer must differ")
        }
        if (forcedHosts.entries.any { it.key.isBlank() || it.value.isBlank() }) {
            throw IllegalArgumentException("forcedHosts must not contain blank hostnames or server names")
        }
    }

    constructor(requireServerAuth: Boolean, authServer: String) : this(
        requireServerAuth, authServer, "hub", emptyMap()
    )

    fun hasBackendServer(): Boolean = backendServer.trim().isNotBlank()

    fun requiringServerAuth(): ProxySettings {
        return if (requireServerAuth) this else ProxySettings(true, authServer, backendServer, forcedHosts)
    }

    companion object {
        @JvmStatic
        fun defaults(): ProxySettings = ProxySettings(true, "auth", "hub", emptyMap())
    }
}
