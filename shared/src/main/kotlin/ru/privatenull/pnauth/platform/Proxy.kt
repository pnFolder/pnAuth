package ru.privatenull.pnauth.platform

import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Clean platform-neutral Proxy abstraction facade interface. */
interface Proxy {
    fun type(): PlatformType
    fun player(uniqueId: UUID): Optional<Player>
    fun player(username: String): Optional<Player>
    fun players(): Collection<Player>
    fun server(name: String): Optional<Server>
    fun servers(): Collection<Server>
    fun transferPlayer(uniqueId: UUID, targetServer: String): CompletableFuture<Boolean>
    fun registerServerRoute(name: String, host: String, port: Int): Boolean
    fun unregisterServerRoute(name: String): Boolean
    fun registerPluginChannel(channel: String)
    fun unregisterPluginChannel(channel: String)
    fun sendPluginMessage(channel: String, data: ByteArray): Boolean
    fun runTask(runnable: Runnable)
    fun runTaskLater(delay: Duration, runnable: Runnable)
    fun runTaskRepeating(delay: Duration, period: Duration, runnable: Runnable): AutoCloseable
    fun shutdown(reason: String)
    fun <T> rawProxy(): T
}
