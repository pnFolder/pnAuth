package ru.privatenull.pnauth.cluster

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Долговечный at-least-once outbox для cluster-событий.
 *
 * Событие сначала атомарно сохраняется на диске, затем отправляется транспорту.
 * При ошибке оно остаётся в каталоге и повторяется после восстановления связи.
 */
internal class PersistentClusterOutbox(
    private val directory: Path,
    private val transport: ClusterTransport
) : AutoCloseable {
    private val json = ObjectMapper()
    private val running = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "pnauth-cluster-outbox").apply { isDaemon = true }
    }

    init {
        Files.createDirectories(directory)
        executor.scheduleWithFixedDelay(::flushSafely, 0, 1, TimeUnit.SECONDS)
    }

    fun enqueue(event: ClusterEvent) {
        val target = path(event.id)
        val temporary = Files.createTempFile(directory, event.id.toString(), ".tmp")
        try {
            Files.write(temporary, json.writeValueAsBytes(StoredEvent.from(event)))
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        flush(target)
    }

    private fun flushSafely() {
        if (!running.get()) return
        runCatching {
            Files.list(directory).use { files ->
                files.filter { it.fileName.toString().endsWith(".event") }
                    .sorted()
                    .forEach(::flush)
            }
        }
    }

    private fun flush(file: Path) {
        if (!Files.exists(file)) return
        runCatching {
            val event = json.readValue(file.toFile(), StoredEvent::class.java).event()
            transport.publish(event)
            Files.deleteIfExists(file)
        }
    }

    fun pending(): Long = Files.list(directory).use { files ->
        files.filter { it.fileName.toString().endsWith(".event") }.count()
    }

    private fun path(id: UUID): Path = directory.resolve("$id.event")

    override fun close() {
        flushSafely()
        running.set(false)
        executor.shutdownNow()
    }

    private class StoredEvent {
        var id: String = ""
        var type: String = ""
        var sourceNode: String = ""
        var playerId: String? = null
        var ticketId: String? = null
        var occurredAt: Long = 0
        var attributes: Map<String, String> = emptyMap()

        fun event() = ClusterEvent(
            UUID.fromString(id),
            ClusterEvent.Type.valueOf(type),
            sourceNode,
            playerId?.let(UUID::fromString),
            ticketId,
            Instant.ofEpochMilli(occurredAt),
            attributes
        )

        companion object {
            fun from(event: ClusterEvent) = StoredEvent().apply {
                id = event.id.toString()
                type = event.type.name
                sourceNode = event.sourceNode
                playerId = event.playerId?.toString()
                ticketId = event.ticketId
                occurredAt = event.occurredAt.toEpochMilli()
                attributes = event.attributes
            }
        }
    }
}
