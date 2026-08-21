package ru.privatenull.pnauth.hub

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HubHttpServer(
    private val config: HubConfig.Runtime,
    private val credentials: HubCredentialService
) : AutoCloseable {
    private val json = ObjectMapper()
    private val authenticator = HubRequestAuthenticator(config.clients, config.timestampToleranceSeconds)
    private val limits = ConcurrentHashMap<String, Window>()
    private val events = HubEventBroker()
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "pnauth-hub-http").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress(config.host, config.port), 0).apply {
        createContext("/api/v1/health", ::health)
        createContext("/api/v1/auth/lookup") { exchange -> secured(exchange, PlayerRequest::class.java, credentials::lookup) }
        createContext("/api/v1/auth/register") { exchange -> secured(exchange, CredentialRequest::class.java, credentials::register) }
        createContext("/api/v1/auth/verify") { exchange -> secured(exchange, CredentialRequest::class.java, credentials::verify) }
        createContext("/api/v1/auth/change-password") { exchange ->
            secured(exchange, ChangePasswordRequest::class.java, credentials::changePassword)
        }
        createContext("/api/v1/totp/begin") { exchange -> secured(exchange, BeginTotpRequest::class.java, credentials::beginTotp) }
        createContext("/api/v1/totp/confirm") { exchange -> secured(exchange, TotpCodeRequest::class.java, credentials::confirmTotp) }
        createContext("/api/v1/totp/verify") { exchange -> secured(exchange, TotpCodeRequest::class.java, credentials::verifyTotp) }
        createContext("/api/v1/totp/disable") { exchange -> secured(exchange, DisableTotpRequest::class.java, credentials::disableTotp) }
        createContext("/api/v1/account/unregister") { exchange -> secured(exchange, UnregisterRequest::class.java, credentials::unregister) }
        createContext("/api/v1/admin/unregister") { exchange -> secured(exchange, UsernameRequest::class.java, credentials::adminUnregister) }
        createContext("/api/v1/admin/change-password") { exchange -> secured(exchange, AdminPasswordRequest::class.java, credentials::adminChangePassword) }
        createContext("/api/v1/admin/register") { exchange -> secured(exchange, AdminPasswordRequest::class.java, credentials::forceRegister) }
        createContext("/api/v1/admin/toggle-premium") { exchange -> secured(exchange, UsernameRequest::class.java, credentials::togglePremium) }
        createContext("/api/v1/account/premium") { exchange -> secured(exchange, UsernameRequest::class.java, credentials::premium) }
        createContext("/api/v1/admission/check") { exchange -> secured(exchange, AdmissionRequest::class.java, credentials::admission) }
        createContext("/api/v1/events/publish") { exchange ->
            secured(exchange, ru.privatenull.pnauth.hub.HubEventWire::class.java) { event ->
                mapOf("sequence" to events.publish(event))
            }
        }
        createContext("/api/v1/events/poll", ::pollEvents)
        executor = this@HubHttpServer.executor
    }

    fun start() = server.start()

    private fun health(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respond(exchange, 405, mapOf("error" to "method_not_allowed"))
        respond(exchange, 200, mapOf("status" to "ok", "service" to "pnauth-hub"))
    }

    private fun pollEvents(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") return respond(exchange, 405, mapOf("error" to "method_not_allowed"))
            val body = ByteArray(0)
            val clientId = authenticator.authenticate("GET", exchange.requestURI.path, exchange.requestHeaders, body)
                ?: return respond(exchange, 401, mapOf("error" to "unauthorized"))
            if (!allow(clientId)) return respond(exchange, 429, mapOf("error" to "rate_limited"))
            val after = exchange.requestURI.rawQuery.orEmpty().split('&')
                .mapNotNull { it.split('=', limit = 2).takeIf { pair -> pair.size == 2 } }
                .firstOrNull { it[0] == "after" }?.get(1)?.toLongOrNull() ?: 0
            respond(exchange, 200, events.poll(after))
        } catch (_: Exception) {
            respond(exchange, 400, mapOf("error" to "invalid_request"))
        } finally {
            exchange.close()
        }
    }

    private fun <T : Any, R : Any> secured(exchange: HttpExchange, type: Class<T>, action: (T) -> R) {
        try {
            if (exchange.requestMethod != "POST") return respond(exchange, 405, mapOf("error" to "method_not_allowed"))
            val body = readBounded(exchange)
            val clientId = authenticator.authenticate(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders, body)
                ?: return respond(exchange, 401, mapOf("error" to "unauthorized"))
            if (!allow(clientId)) return respond(exchange, 429, mapOf("error" to "rate_limited"))
            val request = json.readValue(body, type)
            respond(exchange, 200, action(request))
        } catch (_: RequestTooLargeException) {
            respond(exchange, 413, mapOf("error" to "request_too_large"))
        } catch (_: Exception) {
            respond(exchange, 400, mapOf("error" to "invalid_request"))
        } finally {
            exchange.close()
        }
    }

    private fun readBounded(exchange: HttpExchange): ByteArray {
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > config.maxRequestBytes) throw RequestTooLargeException()
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        exchange.requestBody.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > config.maxRequestBytes) throw RequestTooLargeException()
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun allow(clientId: String): Boolean {
        val minute = System.currentTimeMillis() / 60_000
        val window = limits.compute(clientId) { _, current ->
            if (current == null || current.minute != minute) Window(minute, AtomicInteger()) else current
        }!!
        return window.count.incrementAndGet() <= config.requestsPerMinute
    }

    private fun respond(exchange: HttpExchange, status: Int, value: Any) {
        val bytes = json.writeValueAsBytes(value)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(1)
        executor.shutdownNow()
    }

    private data class Window(val minute: Long, val count: AtomicInteger)
    private class RequestTooLargeException : RuntimeException()
}
