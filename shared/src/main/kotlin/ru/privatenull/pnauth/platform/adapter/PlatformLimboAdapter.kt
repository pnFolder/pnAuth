package ru.privatenull.pnauth.platform.adapter

import ru.privatenull.pnauth.limbo.LimboServer

/** Standardized multi-platform adapter for embedded PicoLimbo authentication routes. */
interface PlatformLimboAdapter : AutoCloseable {
    val isEnabled: Boolean
    val limboServer: LimboServer?
    fun registerRoute(serverName: String, host: String, port: Int)
    fun unregisterRoute(serverName: String)
}
