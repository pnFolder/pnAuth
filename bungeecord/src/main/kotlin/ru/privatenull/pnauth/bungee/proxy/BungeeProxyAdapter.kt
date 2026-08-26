package ru.privatenull.pnauth.bungee.proxy

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.config.ServerInfo
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.Plugin
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.PlatformType
import ru.privatenull.pnauth.platform.Player
import ru.privatenull.pnauth.platform.Proxy
import ru.privatenull.pnauth.platform.Server
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BungeeProxyAdapter(
    private val plugin: Plugin,
    private val proxy: ProxyServer,
    private val platform: Platform
) : Proxy {

    override fun type(): PlatformType = PlatformType.BUNGEECORD

    override fun player(uniqueId: UUID): Optional<Player> {
        return platform.player(uniqueId)
    }

    override fun player(username: String): Optional<Player> {
        return platform.player(username)
    }

    override fun players(): Collection<Player> {
        return platform.players()
    }

    override fun server(name: String): Optional<Server> {
        val info = proxy.getServerInfo(name) ?: return Optional.empty()
        return Optional.of(BungeeServer(info, platform))
    }

    override fun servers(): Collection<Server> {
        return proxy.servers.values.map { BungeeServer(it, platform) }
    }

    override fun transferPlayer(uniqueId: UUID, targetServer: String): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val player: ProxiedPlayer? = proxy.getPlayer(uniqueId)
        val target: ServerInfo? = proxy.getServerInfo(targetServer)

        if (player == null || target == null) {
            future.complete(false)
            return future
        }

        player.connect(target) { success, _ ->
            future.complete(java.lang.Boolean.TRUE.equals(success))
        }
        return future
    }

    override fun registerServerRoute(name: String, host: String, port: Int): Boolean {
        return try {
            val serverInfo = proxy.constructServerInfo(
                name,
                InetSocketAddress(host, port),
                "pnAuth authentication limbo",
                false
            )
            proxy.servers[name] = serverInfo
            proxy.logger.info("[pnAuth] Virtual auth route '$name' registered successfully at $host:$port.")
            true
        } catch (e: Exception) {
            proxy.logger.warning("[pnAuth] Failed to register virtual auth route '$name': ${e.message}")
            false
        }
    }

    override fun unregisterServerRoute(name: String): Boolean {
        return try {
            proxy.servers.remove(name)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun registerPluginChannel(channel: String) {
        proxy.registerChannel(channel)
    }

    override fun unregisterPluginChannel(channel: String) {
        proxy.unregisterChannel(channel)
    }

    override fun sendPluginMessage(channel: String, data: ByteArray): Boolean {
        return try {
            proxy.players.firstOrNull()?.sendData(channel, data)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun runTask(runnable: Runnable) {
        proxy.scheduler.runAsync(plugin, runnable)
    }

    override fun runTaskLater(delay: Duration, runnable: Runnable) {
        proxy.scheduler.schedule(plugin, runnable, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun runTaskRepeating(delay: Duration, period: Duration, runnable: Runnable): AutoCloseable {
        val task = proxy.scheduler.schedule(plugin, runnable, delay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS)
        return AutoCloseable { task.cancel() }
    }

    override fun shutdown(reason: String) {
        proxy.stop(reason)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> rawProxy(): T {
        return proxy as T
    }

    private class BungeeServer(
        private val info: ServerInfo,
        private val platform: Platform
    ) : Server {
        override fun name(): String = info.name
        override fun address(): InetSocketAddress = info.socketAddress as InetSocketAddress
        override fun playerCount(): Int = info.players.size
        override fun players(): Collection<Player> = info.players.mapNotNull { platform.player(it.uniqueId).orElse(null) }
        override fun sendPluginMessage(channel: String, data: ByteArray): Boolean {
            return try {
                info.sendData(channel, data)
                true
            } catch (e: Exception) {
                false
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> rawServer(): T = info as T
    }
}
