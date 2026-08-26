package ru.privatenull.pnauth.cluster

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Redis Streams transport с отдельной позицией чтения каждого узла и без credential-данных. */
class RedisClusterTransport(
    redisUri: String,
    private val stream: String,
    private val nodeId: String
) : ClusterTransport {
    private val endpoint = RedisEndpoint.parse(redisUri)
    private val json = ObjectMapper()
    private val listeners = CopyOnWriteArrayList<Consumer<ClusterEvent>>()
    private val running = AtomicBoolean(true)
    private val healthy = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pnauth-cluster-redis-$nodeId").apply { isDaemon = true }
    }
    @Volatile private var lastId = "${System.currentTimeMillis()}-0"

    init {
        executor.execute(::readLoop)
    }

    override fun publish(event: ClusterEvent) {
        require(event.sourceNode == nodeId) { "Cluster event source does not match this node" }
        rejectSensitive(event.attributes)
        RedisRespConnection(endpoint).use { connection ->
            connection.command(
                "XADD", stream, "MAXLEN", "~", "100000", "*",
                "event_id", event.id.toString(), "event_type", event.type.name,
                "source_node", event.sourceNode, "player_id", event.playerId?.toString().orEmpty(),
                "ticket_id", event.ticketId.orEmpty(), "occurred_at", event.occurredAt.toEpochMilli().toString(),
                "attributes", json.writeValueAsString(event.attributes)
            ).throwIfError()
            healthy.set(true)
        }
    }

    override fun subscribe(listener: Consumer<ClusterEvent>): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun readLoop() {
        while (running.get()) {
            try {
                RedisRespConnection(endpoint).use { connection ->
                    while (running.get()) {
                        val response = connection.command("XREAD", "BLOCK", "5000", "COUNT", "100", "STREAMS", stream, lastId)
                        response.throwIfError()
                        parseRead(response)?.forEach { (redisId, event) ->
                            lastId = redisId
                            if (event.sourceNode != nodeId) listeners.forEach { listener -> runCatching { listener.accept(event) } }
                        }
                        healthy.set(true)
                    }
                }
            } catch (_: Exception) {
                healthy.set(false)
                if (running.get()) Thread.sleep(1000)
            }
        }
    }

    private fun parseRead(response: RespValue): List<Pair<String, ClusterEvent>>? {
        val streams = (response as? RespValue.Array)?.values ?: return null
        val result = ArrayList<Pair<String, ClusterEvent>>()
        for (streamEntry in streams) {
            val streamParts = (streamEntry as? RespValue.Array)?.values ?: continue
            val entries = (streamParts.getOrNull(1) as? RespValue.Array)?.values ?: continue
            for (entry in entries) {
                val parts = (entry as? RespValue.Array)?.values ?: continue
                val redisId = parts.getOrNull(0)?.text() ?: continue
                val fields = (parts.getOrNull(1) as? RespValue.Array)?.values ?: continue
                val map = LinkedHashMap<String, String>()
                var index = 0
                while (index + 1 < fields.size) {
                    map[fields[index].text()] = fields[index + 1].text()
                    index += 2
                }
                val attributes = json.readValue(map["attributes"] ?: "{}", object : TypeReference<Map<String, String>>() {})
                rejectSensitive(attributes)
                result += redisId to ClusterEvent(
                    UUID.fromString(map.getValue("event_id")), ClusterEvent.Type.valueOf(map.getValue("event_type")),
                    map.getValue("source_node"), map["player_id"]?.takeIf(String::isNotBlank)?.let(UUID::fromString),
                    map["ticket_id"]?.takeIf(String::isNotBlank), Instant.ofEpochMilli(map.getValue("occurred_at").toLong()), attributes
                )
            }
        }
        return result
    }

    private fun rejectSensitive(attributes: Map<String, String>) {
        require(attributes.keys.none { key -> SENSITIVE.any { key.contains(it, true) } }) {
            "Sensitive attributes are forbidden in Redis cluster events"
        }
    }

    override fun healthy(): Boolean = healthy.get()

    override fun close() {
        running.set(false)
        executor.shutdownNow()
        listeners.clear()
    }

    private companion object {
        val SENSITIVE = listOf("password", "secret", "token", "recovery", "credential", "hash")
    }
}

private data class RedisEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String?,
    val password: String?,
    val database: Int
) {
    companion object {
        fun parse(value: String): RedisEndpoint {
            val uri = URI.create(value)
            require(uri.scheme == "redis" || uri.scheme == "rediss") { "Unsupported Redis URI scheme" }
            require(!uri.host.isNullOrBlank()) { "Redis host is required" }
            val userInfo = uri.rawUserInfo?.split(':', limit = 2)
            val username = userInfo?.getOrNull(0)?.takeIf { it.isNotBlank() }?.decode()
            val password = userInfo?.getOrNull(1)?.decode()
            val database = uri.path.removePrefix("/").takeIf { it.isNotBlank() }?.toInt() ?: 0
            require(database >= 0) { "Redis database must not be negative" }
            return RedisEndpoint(uri.host, if (uri.port > 0) uri.port else 6379, uri.scheme == "rediss", username, password, database)
        }

        private fun String.decode() = URLDecoder.decode(this, StandardCharsets.UTF_8)
    }
}

private class RedisRespConnection(private val endpoint: RedisEndpoint) : AutoCloseable {
    private val socket: Socket = createSocket(endpoint)
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    init {
        socket.soTimeout = 15_000
        if (endpoint.password != null) {
            val auth = if (endpoint.username == null) command("AUTH", endpoint.password) else
                command("AUTH", endpoint.username, endpoint.password)
            auth.throwIfError()
        }
        if (endpoint.database != 0) command("SELECT", endpoint.database.toString()).throwIfError()
    }

    fun command(vararg arguments: String): RespValue {
        output.write("*${arguments.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
        arguments.forEach { argument ->
            val bytes = argument.toByteArray(StandardCharsets.UTF_8)
            output.write("\$${bytes.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(bytes)
            output.write(CRLF)
        }
        output.flush()
        return readValue()
    }

    private fun readValue(): RespValue = when (val prefix = input.read()) {
        '+'.code -> RespValue.Text(readLine())
        '-'.code -> RespValue.Error(readLine())
        ':'.code -> RespValue.Integer(readLine().toLong())
        '$'.code -> {
            val length = readLine().toInt()
            if (length < 0) RespValue.Null else RespValue.Text(String(readExact(length), StandardCharsets.UTF_8).also { readCrLf() })
        }
        '*'.code -> {
            val size = readLine().toInt()
            if (size < 0) RespValue.Null else RespValue.Array(List(size) { readValue() })
        }
        -1 -> throw EOFException("Redis closed the connection")
        else -> throw IllegalStateException("Unknown Redis RESP prefix: $prefix")
    }

    private fun readLine(): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("Redis closed the connection")
            if (value == '\r'.code) {
                if (input.read() != '\n'.code) throw IllegalStateException("Invalid Redis line ending")
                return bytes.toByteArray().toString(StandardCharsets.UTF_8)
            }
            bytes += value.toByte()
        }
    }

    private fun readExact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read < 0) throw EOFException("Redis closed the connection")
            offset += read
        }
        return result
    }

    private fun readCrLf() {
        if (input.read() != '\r'.code || input.read() != '\n'.code) throw IllegalStateException("Invalid Redis bulk ending")
    }

    override fun close() = socket.close()

    companion object {
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        private fun createSocket(endpoint: RedisEndpoint): Socket {
            if (!endpoint.tls) return Socket(endpoint.host, endpoint.port)
            val socket = SSLSocketFactory.getDefault().createSocket(endpoint.host, endpoint.port) as SSLSocket
            socket.sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.startHandshake()
            return socket
        }
    }
}

private sealed interface RespValue {
    data class Text(val value: String) : RespValue
    data class Error(val value: String) : RespValue
    data class Integer(val value: Long) : RespValue
    data class Array(val values: List<RespValue>) : RespValue
    data object Null : RespValue

    fun text(): String = when (this) {
        is Text -> value
        is Integer -> value.toString()
        else -> throw IllegalStateException("Redis value is not text")
    }

    fun throwIfError() {
        if (this is Error) throw IllegalStateException("Redis error: $value")
    }
}
