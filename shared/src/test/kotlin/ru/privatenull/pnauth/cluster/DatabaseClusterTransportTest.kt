package ru.privatenull.pnauth.cluster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DatabaseClusterTransportTest {
    @Test
    fun `delivers events to another node without secrets`(@TempDir directory: Path) {
        val url = "jdbc:sqlite:${directory.resolve("cluster.db")}" 
        DatabaseClusterTransport(url, "", "", "node-a", Duration.ofMillis(20)).use { first ->
            DatabaseClusterTransport(url, "", "", "node-b", Duration.ofMillis(20)).use { second ->
                val latch = CountDownLatch(1)
                var received: ClusterEvent? = null
                second.subscribe { event -> received = event; latch.countDown() }
                val player = UUID.randomUUID()
                first.publish(
                    ClusterEvent(
                        UUID.randomUUID(), ClusterEvent.Type.ACCOUNT_CHANGED, "node-a",
                        player, null, Instant.now(), mapOf("reason" to "password-changed")
                    )
                )

                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertEquals(player, received?.playerId)
                assertEquals(ClusterEvent.Type.ACCOUNT_CHANGED, received?.type)
            }
        }
    }

    @Test
    fun `rejects sensitive event attributes`(@TempDir directory: Path) {
        val transport = DatabaseClusterTransport(
            "jdbc:sqlite:${directory.resolve("cluster.db")}", "", "", "node-a", Duration.ofSeconds(1)
        )
        transport.use {
            assertThrows(IllegalArgumentException::class.java) {
                it.publish(
                    ClusterEvent(
                        UUID.randomUUID(), ClusterEvent.Type.ACCOUNT_CHANGED, "node-a", null, null,
                        Instant.now(), mapOf("passwordHash" to "forbidden")
                    )
                )
            }
        }
    }
}
