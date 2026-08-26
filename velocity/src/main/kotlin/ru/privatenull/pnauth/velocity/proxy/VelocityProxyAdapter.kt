package ru.privatenull.pnauth.velocity.proxy

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import org.slf4j.Logger
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class VelocityProxyAdapter(
    private val plugin: Any,
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val platform: Platform
) : Proxy {

    private val registeredLimboServers = ConcurrentHashMap<String, RegisteredServer>()

    override fun type(): PlatformType = PlatformType.VELOCITY

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
        return proxy.getServer(name).map { VelocityServer(it, platform) }
    }

    override fun servers(): Collection<Server> {
        return proxy.allServers.map { VelocityServer(it, platform) }
    }

    override fun transferPlayer(uniqueId: UUID, targetServer: String): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val player = proxy.getPlayer(uniqueId).orElse(null)
        val target = proxy.getServer(targetServer).orElse(null)

        if (player == null || target == null) {
            future.complete(false)
            return future
        }

        player.createConnectionRequest(target).connect().thenAccept { result ->
            future.complete(result.isSuccessful)
        }
        return future
    }

    override fun registerServerRoute(name: String, host: String, port: Int): Boolean {
        return try {
            val serverInfo = ServerInfo(name, InetSocketAddress(host, port))
            val registered = proxy.registerServer(serverInfo)
            registeredLimboServers[name] = registered
            logger.info("Virtual auth route '$name' registered successfully at $host:$port.")
            true
        } catch (e: Exception) {
            logger.warn("Failed to register virtual auth route '$name': ${e.message}")
            false
        }
    }

    override fun unregisterServerRoute(name: String): Boolean {
        return try {
            registeredLimboServers.remove(name)?.let { registered ->
                proxy.unregisterServer(registered.serverInfo)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun registerPluginChannel(channel: String) {
        val parts = channel.split(":", limit = 2)
        if (parts.size == 2) {
            proxy.channelRegistrar.register(MinecraftChannelIdentifier.create(parts[0], parts[1]))
        }
    }

    override fun unregisterPluginChannel(channel: String) {
        val parts = channel.split(":", limit = 2)
        if (parts.size == 2) {
            proxy.channelRegistrar.unregister(MinecraftChannelIdentifier.create(parts[0], parts[1]))
        }
    }

    override fun sendPluginMessage(channel: String, data: ByteArray): Boolean {
        return try {
            val parts = channel.split(":", limit = 2)
            if (parts.size == 2) {
                val identifier = MinecraftChannelIdentifier.create(parts[0], parts[1])
                proxy.allPlayers.firstOrNull()?.sendPluginMessage(identifier, data)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    override fun runTask(runnable: Runnable) {
        proxy.scheduler.buildTask(plugin, runnable).schedule()
    }

    override fun runTaskLater(delay: Duration, runnable: Runnable) {
        proxy.scheduler.buildTask(plugin, runnable)
            .delay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .schedule()
    }

    override fun runTaskRepeating(delay: Duration, period: Duration, runnable: Runnable): AutoCloseable {
        val task = proxy.scheduler.buildTask(plugin, runnable)
            .delay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .repeat(period.toMillis(), TimeUnit.MILLISECONDS)
            .schedule()
        return AutoCloseable { task.cancel() }
    }

    override fun shutdown(reason: String) {
        proxy.shutdown(net.kyori.adventure.text.Component.text(reason))
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> rawProxy(): T {
        return proxy as T
    }

    private class VelocityServer(
        private val server: RegisteredServer,
        private val platform: Platform
    ) : Server {
        override fun name(): String = server.serverInfo.name
        override fun address(): InetSocketAddress = server.serverInfo.address
        override fun playerCount(): Int = server.playersConnected.size
        override fun players(): Collection<Player> = server.playersConnected.mapNotNull { platform.player(it.uniqueId).orElse(null) }
        override fun sendPluginMessage(channel: String, data: ByteArray): Boolean {
            return try {
                val parts = channel.split(":", limit = 2)
                if (parts.size == 2) {
                    val identifier = MinecraftChannelIdentifier.create(parts[0], parts[1])
                    server.sendPluginMessage(identifier, data)
                } else false
            } catch (e: Exception) {
                false
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> rawServer(): T = server as T
    }
}
