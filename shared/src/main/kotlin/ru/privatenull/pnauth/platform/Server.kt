package ru.privatenull.pnauth.platform

import java.net.InetSocketAddress

/** Clean platform-neutral Server abstraction interface. */
interface Server {
    fun name(): String
    fun address(): InetSocketAddress
    fun playerCount(): Int
    fun players(): Collection<Player>
    fun sendPluginMessage(channel: String, data: ByteArray): Boolean
    fun <T> rawServer(): T
}
