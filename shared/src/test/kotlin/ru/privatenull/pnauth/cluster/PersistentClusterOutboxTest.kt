package ru.privatenull.pnauth.cluster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

class PersistentClusterOutboxTest {
    @Test
    fun `keeps failed event and retries until transport accepts it`(@TempDir directory: Path) {
        val transport = FlakyTransport()
        val event = ClusterEvent(
            UUID.randomUUID(), ClusterEvent.Type.ACCOUNT_CHANGED, "node-a",
            UUID.randomUUID(), null, Instant.now()
        )

        PersistentClusterOutbox(directory, transport).use { outbox ->
            outbox.enqueue(event)
            assertEquals(1, outbox.pending())
            transport.available.set(true)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (outbox.pending() != 0L && System.nanoTime() < deadline) Thread.sleep(20)

            assertEquals(0, outbox.pending())
            assertTrue(transport.published.any { it.id == event.id })
            assertTrue(Files.list(directory).use { it.findAny().isEmpty })
        }
    }

    private class FlakyTransport : ClusterTransport {
        val available = AtomicBoolean(false)
        val published = CopyOnWriteArrayList<ClusterEvent>()

        override fun publish(event: ClusterEvent) {
            if (!available.get()) throw IllegalStateException("transport unavailable")
            published += event
        }

        override fun subscribe(listener: Consumer<ClusterEvent>): AutoCloseable = AutoCloseable {}
        override fun healthy(): Boolean = available.get()
        override fun close() = Unit
    }
}

