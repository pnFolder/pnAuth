package ru.privatenull.pnauth.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.platform.PlatformType
import ru.privatenull.pnauth.platform.Player
import ru.privatenull.pnauth.platform.Proxy
import ru.privatenull.pnauth.platform.Server
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ServerBalancerTest {

    @Test
    fun leastPlayersBalancerSelectsLowestCount() {
        val proxy = FakeProxy(
            mapOf(
                "hub-1" to 50,
                "hub-2" to 10,
                "hub-3" to 80
            )
        )
        val balancer = ServerBalancerFactory.create(ServerBalancerMode.LEAST_PLAYERS)
        val selected = balancer.selectServer(listOf("hub-1", "hub-2", "hub-3"), proxy)

        assertTrue(selected.isPresent)
        assertEquals("hub-2", selected.get())
    }

    @Test
    fun lowestLoadPercentSelectsServerWithLowestRatio() {
        // hub-small has 40/50 players (80% load)
        // hub-large has 100/250 players (40% load) -> should be selected!
        val proxy = FakeProxy(
            mapOf(
                "hub-small" to 40,
                "hub-large" to 100
            )
        )
        val limits = mapOf("hub-small" to 50, "hub-large" to 250)
        val balancer = ServerBalancerFactory.create(
            ServerBalancerMode.LOWEST_LOAD_PERCENT,
            maxPlayersPerServer = 100,
            serverLimits = limits
        )
        val selected = balancer.selectServer(listOf("hub-small", "hub-large"), proxy)

        assertTrue(selected.isPresent)
        assertEquals("hub-large", selected.get())
    }

    @Test
    fun firstAvailableBalancerSelectsFirstExisting() {
        val proxy = FakeProxy(mapOf("hub-2" to 5))
        val balancer = ServerBalancerFactory.create(ServerBalancerMode.FIRST_AVAILABLE)
        val selected = balancer.selectServer(listOf("offline-1", "hub-2", "hub-3"), proxy)

        assertTrue(selected.isPresent)
        assertEquals("hub-2", selected.get())
    }

    @Test
    fun fillingBalancerFillsUntilMaxPlayersReached() {
        val proxy = FakeProxy(
            mapOf(
                "hub-1" to 100,
                "hub-2" to 40,
                "hub-3" to 0
            )
        )
        val balancer = ServerBalancerFactory.create(ServerBalancerMode.FILLING, maxPlayersPerServer = 100)
        val selected = balancer.selectServer(listOf("hub-1", "hub-2", "hub-3"), proxy)

        assertTrue(selected.isPresent)
        assertEquals("hub-2", selected.get())
    }

    private class FakeProxy(
        private val servers: Map<String, Int>
    ) : Proxy {
        override fun type(): PlatformType = PlatformType.BUNGEECORD
        override fun player(uniqueId: UUID): Optional<Player> = Optional.empty()
        override fun player(username: String): Optional<Player> = Optional.empty()
        override fun players(): Collection<Player> = emptyList()

        override fun server(name: String): Optional<Server> {
            val count = servers[name] ?: return Optional.empty()
            return Optional.of(FakeServer(name, count))
        }

        override fun servers(): Collection<Server> {
            return servers.entries.map { FakeServer(it.key, it.value) }
        }

        override fun transferPlayer(uniqueId: UUID, targetServer: String): CompletableFuture<Boolean> {
            return CompletableFuture.completedFuture(true)
        }

        override fun registerServerRoute(name: String, host: String, port: Int): Boolean = true
        override fun unregisterServerRoute(name: String): Boolean = true
        override fun registerPluginChannel(channel: String) {}
        override fun unregisterPluginChannel(channel: String) {}
        override fun sendPluginMessage(channel: String, data: ByteArray): Boolean = true
        override fun runTask(runnable: Runnable) {}
        override fun runTaskLater(delay: Duration, runnable: Runnable) {}
        override fun runTaskRepeating(delay: Duration, period: Duration, runnable: Runnable): AutoCloseable {
            return AutoCloseable {}
        }
        override fun shutdown(reason: String) {}
        @Suppress("UNCHECKED_CAST")
        override fun <T> rawProxy(): T = throw UnsupportedOperationException()
    }

    private class FakeServer(
        private val name: String,
        private val count: Int
    ) : Server {
        override fun name(): String = name
        override fun address(): InetSocketAddress = InetSocketAddress("127.0.0.1", 25565)
        override fun playerCount(): Int = count
        override fun players(): Collection<Player> = emptyList()
        override fun sendPluginMessage(channel: String, data: ByteArray): Boolean = true
        @Suppress("UNCHECKED_CAST")
        override fun <T> rawServer(): T = throw UnsupportedOperationException()
    }
}
