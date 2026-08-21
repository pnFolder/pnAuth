package ru.privatenull.pnauth.verification

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.privatenull.pnauth.config.ExternalVerificationSettings
import ru.privatenull.pnauth.extension.AuthExtensionRegistration
import ru.privatenull.pnauth.extension.AuthExtensionRegistry
import ru.privatenull.pnauth.extension.AuthPolicyDecision
import ru.privatenull.pnauth.extension.VerificationTicket
import ru.privatenull.pnauth.platform.adapter.PlatformLoggerAdapter
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Встроенный транспорт внешнего подтверждения без платформенной auth-логики. */
class ExternalVerificationService(
    private val settings: ExternalVerificationSettings,
    private val registry: AuthExtensionRegistry,
    private val logger: PlatformLoggerAdapter
) : AutoCloseable {
    private val json = ObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "pnauth-verification").apply { isDaemon = true }
    }
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val registrations = ArrayList<AuthExtensionRegistration>()
    private var server: HttpServer? = null

    fun start() {
        if (!settings.enabled) return
        val callback = settings.callback
        server = HttpServer.create(InetSocketAddress(callback.host, callback.port), 0).apply {
            createContext("/pnauth/verification", ::handleCallback)
            executor = this@ExternalVerificationService.executor
            start()
        }
        registrations += registry.register("pnauth:external-verification", 10_000) { context ->
            val decision = if (context.operation in settings.operations) {
                AuthPolicyDecision.requireVerification(
                    "external", "Подтвердите действие во внешнем мессенджере", settings.lifetime
                )
            } else AuthPolicyDecision.allow()
            CompletableFuture.completedFuture(decision)
        }
        registrations += registry.onTicket { ticket ->
            if (ticket.provider == "external" && ticket.status == VerificationTicket.Status.PENDING) {
                executor.execute { send(ticket) }
            }
        }
        logger.info("Внешнее подтверждение включено; callback слушает ${callback.host}:${callback.port}.")
    }

    private fun send(ticket: VerificationTicket) {
        val approve = link(ticket.id, "approve")
        val deny = link(ticket.id, "deny")
        val text = buildString {
            append("pnAuth: запрос подтверждения\n")
            append("Игрок: ").append(ticket.username ?: "неизвестен").append('\n')
            append("Действие: ").append(ticket.operation.name).append('\n')
            append("Разрешить: ").append(approve).append('\n')
            append("Отклонить: ").append(deny)
        }
        if (settings.discord.enabled) postJson(
            "Discord", settings.discord.webhookUrl,
            mapOf(
                "content" to text.substringBefore("Разрешить:"),
                "components" to listOf(
                    mapOf("type" to 1, "components" to listOf(
                        mapOf("type" to 2, "style" to 5, "label" to "Разрешить", "url" to approve),
                        mapOf("type" to 2, "style" to 5, "label" to "Отклонить", "url" to deny)
                    ))
                )
            )
        )
        if (settings.telegram.enabled) postForm(
            "Telegram", "https://api.telegram.org/bot${settings.telegram.botToken}/sendMessage",
            mapOf(
                "chat_id" to settings.telegram.chatId,
                "text" to text.substringBefore("Разрешить:"),
                "disable_web_page_preview" to "true",
                "reply_markup" to json.writeValueAsString(mapOf("inline_keyboard" to listOf(listOf(
                    mapOf("text" to "✅ Разрешить", "url" to approve),
                    mapOf("text" to "⛔ Отклонить", "url" to deny)
                ))))
            )
        )
        if (settings.vk.enabled) postForm(
            "VK", "https://api.vk.com/method/messages.send",
            mapOf(
                "access_token" to settings.vk.accessToken, "v" to settings.vk.apiVersion,
                "peer_id" to settings.vk.peerId,
                "random_id" to SecureRandom().nextInt().toString(),
                "message" to text.substringBefore("Разрешить:"),
                "keyboard" to json.writeValueAsString(mapOf("inline" to true, "buttons" to listOf(listOf(
                    mapOf("action" to mapOf("type" to "open_link", "link" to approve, "label" to "Разрешить"), "color" to "positive"),
                    mapOf("action" to mapOf("type" to "open_link", "link" to deny, "label" to "Отклонить"), "color" to "negative")
                ))))
            )
        )
    }

    private fun postJson(provider: String, url: String, body: Any) {
        post(provider, url, "application/json", json.writeValueAsString(body))
    }

    private fun postForm(provider: String, url: String, values: Map<String, String>) {
        val body = values.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        post(provider, url, "application/x-www-form-urlencoded", body)
    }

    private fun post(provider: String, url: String, contentType: String, body: String) {
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { response, error ->
                if (error != null) logger.warn("Не удалось отправить запрос подтверждения через $provider.", error)
                else if (response.statusCode() !in 200..299) {
                    logger.warn("$provider вернул HTTP ${response.statusCode()} при отправке подтверждения.")
                }
            }
        } catch (error: RuntimeException) {
            logger.warn("Некорректная конфигурация транспорта $provider.", error)
        }
    }

    private fun handleCallback(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.removePrefix("/pnauth/verification/")
            if (path.isBlank() || path.contains('/')) return respond(exchange, 404, "Запрос не найден.")
            val query = query(exchange.requestURI.rawQuery)
            val decision = query["decision"] ?: return respond(exchange, 400, "Не указано решение.")
            val token = query["token"] ?: return respond(exchange, 400, "Не указан защитный токен.")
            if (decision != "approve" && decision != "deny") return respond(exchange, 400, "Неизвестное решение.")
            if (!MessageDigest.isEqual(sign(path, decision).toByteArray(), token.toByteArray())) {
                return respond(exchange, 403, "Ссылка недействительна.")
            }
            if (exchange.requestMethod == "GET") {
                val action = exchange.requestURI.rawPath + "?decision=${encode(decision)}&token=${encode(token)}"
                val label = if (decision == "approve") "Подтвердить действие" else "Отклонить действие"
                return respondHtml(exchange, 200, confirmationPage(action, label))
            }
            if (exchange.requestMethod != "POST") return respond(exchange, 405, "Разрешены только GET и POST.")
            val updated = if (decision == "approve") registry.approve(path) else registry.deny(path)
            if (!updated) return respond(exchange, 410, "Запрос уже обработан или срок ссылки истёк.")
            val result = if (decision == "approve") "Действие подтверждено. Можно закрыть эту страницу." else
                "Действие отклонено. Можно закрыть эту страницу."
            respond(exchange, 200, result)
        } catch (error: RuntimeException) {
            logger.warn("Ошибка обработки внешнего подтверждения.", error)
            respond(exchange, 500, "Внутренняя ошибка pnAuth.")
        } finally {
            exchange.close()
        }
    }

    private fun link(ticketId: String, decision: String): String =
        "${settings.callback.publicUrl}/pnauth/verification/$ticketId?decision=$decision&token=${encode(sign(ticketId, decision))}"

    private fun sign(ticketId: String, decision: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("$ticketId:$decision".toByteArray(StandardCharsets.UTF_8)))
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&')
        .mapNotNull { entry -> entry.split('=', limit = 2).takeIf { it.size == 2 } }
        .associate { it[0] to java.net.URLDecoder.decode(it[1], StandardCharsets.UTF_8) }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        writeResponse(exchange, status, bytes)
    }

    private fun respondHtml(exchange: HttpExchange, status: Int, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        writeResponse(exchange, status, bytes)
    }

    private fun writeResponse(exchange: HttpExchange, status: Int, bytes: ByteArray) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun confirmationPage(action: String, label: String): String = """
        <!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <title>pnAuth — подтверждение</title><style>
        body{font:16px system-ui;background:#111827;color:#f9fafb;display:grid;place-items:center;min-height:100vh;margin:0}
        main{max-width:34rem;padding:2rem;border:1px solid #374151;border-radius:1rem;background:#1f2937;text-align:center}
        button{font:inherit;font-weight:700;padding:.8rem 1.2rem;border:0;border-radius:.6rem;cursor:pointer}
        </style></head><body><main><h1>pnAuth</h1><p>Проверьте данные запроса в мессенджере.</p>
        <form method="post" action="$action"><button type="submit">$label</button></form></main></body></html>
    """.trimIndent()

    override fun close() {
        registrations.asReversed().forEach { it.close() }
        registrations.clear()
        server?.stop(0)
        server = null
        executor.shutdownNow()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
