package ru.privatenull.pnauth.cluster

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Надёжная синхронизация небольшой сети через ту же SQL-базу, без секретов в событиях. */
class DatabaseClusterTransport(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
    private val nodeId: String,
    pollInterval: Duration = Duration.ofMillis(500)
) : ClusterTransport {
    private val json = ObjectMapper()
    private val listeners = CopyOnWriteArrayList<Consumer<ClusterEvent>>()
    private val seen = ConcurrentHashMap.newKeySet<UUID>()
    private val running = AtomicBoolean(true)
    private val healthy = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "pnauth-cluster-db-$nodeId").apply { isDaemon = true }
    }
    @Volatile private var cursor = System.currentTimeMillis()
    @Volatile private var lastCleanup = 0L

    init {
        initialize()
        executor.scheduleWithFixedDelay(::pollSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun publish(event: ClusterEvent) {
        require(event.sourceNode == nodeId) { "Cluster event source does not match this node" }
        require(event.attributes.keys.none { key -> SENSITIVE.any { key.contains(it, true) } }) {
            "Sensitive attributes are forbidden in cluster events"
        }
        open().use { connection ->
            connection.prepareStatement(
                "INSERT INTO pnauth_cluster_events " +
                    "(event_id, event_type, source_node, player_id, ticket_id, occurred_at, attributes_json) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, event.id.toString())
                statement.setString(2, event.type.name)
                statement.setString(3, event.sourceNode)
                statement.setString(4, event.playerId?.toString())
                statement.setString(5, event.ticketId)
                statement.setLong(6, event.occurredAt.toEpochMilli())
                statement.setString(7, json.writeValueAsString(event.attributes))
                statement.executeUpdate()
            }
        }
    }

    override fun subscribe(listener: Consumer<ClusterEvent>): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun pollSafely() {
        if (!running.get()) return
        try {
            poll()
            cleanupExpiredEvents()
            healthy.set(true)
        } catch (_: RuntimeException) {
            healthy.set(false)
        }
    }

    private fun cleanupExpiredEvents() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup < Duration.ofHours(1).toMillis()) return
        lastCleanup = now
        val cutoff = now - Duration.ofDays(7).toMillis()
        open().use { connection ->
            connection.prepareStatement("DELETE FROM pnauth_cluster_events WHERE occurred_at < ?").use { statement ->
                statement.setLong(1, cutoff)
                statement.executeUpdate()
            }
        }
    }

    private fun poll() {
        var nextCursor = cursor
        open().use { connection ->
            connection.prepareStatement(
                "SELECT event_id, event_type, source_node, player_id, ticket_id, occurred_at, attributes_json " +
                    "FROM pnauth_cluster_events WHERE occurred_at >= ? ORDER BY occurred_at, event_id"
            ).use { statement ->
                statement.setLong(1, cursor)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val id = UUID.fromString(result.getString("event_id"))
                        val occurred = result.getLong("occurred_at")
                        if (occurred > nextCursor) nextCursor = occurred
                        if (!seen.add(id)) continue
                        val source = result.getString("source_node")
                        if (source == nodeId) continue
                        val player = result.getString("player_id")?.takeIf { it.isNotBlank() }?.let(UUID::fromString)
                        val attributes = json.readValue(
                            result.getString("attributes_json"), object : TypeReference<Map<String, String>>() {}
                        )
                        val event = ClusterEvent(
                            id, ClusterEvent.Type.valueOf(result.getString("event_type")), source, player,
                            result.getString("ticket_id"), Instant.ofEpochMilli(occurred), attributes
                        )
                        listeners.forEach { listener -> runCatching { listener.accept(event) } }
                    }
                }
            }
        }
        cursor = nextCursor
        if (seen.size > 10_000) seen.clear()
    }

    private fun initialize() {
        open().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS pnauth_cluster_events (" +
                        "event_id VARCHAR(36) PRIMARY KEY, event_type VARCHAR(64) NOT NULL, " +
                        "source_node VARCHAR(64) NOT NULL, player_id VARCHAR(36), ticket_id VARCHAR(64), " +
                        "occurred_at BIGINT NOT NULL, attributes_json VARCHAR(4096) NOT NULL)"
                )
                runCatching {
                    statement.executeUpdate("CREATE INDEX pnauth_cluster_time_idx ON pnauth_cluster_events (occurred_at)")
                }
            }
        }
    }

    private fun open() = try {
        DriverManager.getConnection(jdbcUrl, username, password)
    } catch (error: SQLException) {
        throw IllegalStateException("Could not connect to shared cluster database", error)
    }

    override fun healthy(): Boolean = healthy.get()

    override fun close() {
        running.set(false)
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) healthy.set(false)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            healthy.set(false)
        }
        listeners.clear()
        seen.clear()
    }

    private companion object {
        val SENSITIVE = listOf("password", "secret", "token", "recovery", "credential", "hash")
    }
}
