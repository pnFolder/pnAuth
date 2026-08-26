package ru.privatenull.pnauth.hub

import com.fasterxml.jackson.databind.ObjectMapper
import ru.privatenull.pnauth.config.ClusterSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import ru.privatenull.pnauth.api.TotpSetup
import ru.privatenull.pnauth.cluster.ClusterEvent
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Подписанный HTTPS-клиент Hub. Credential-ответы принципиально не содержат password hash. */
class HubApiClient(private val settings: ClusterSettings.Hub) {
    private val json = ObjectMapper()
    private val random = SecureRandom()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(settings.connectTimeoutMillis.toLong()))
        .build()

    fun lookup(uniqueId: UUID, username: String): CompletableFuture<HubCredentialResult> =
        post("/api/v1/auth/lookup", mapOf("uniqueId" to uniqueId.toString(), "username" to username))

    fun register(uniqueId: UUID, username: String, password: String, ip: String?): CompletableFuture<HubCredentialResult> =
        post(
            "/api/v1/auth/register",
            mapOf("uniqueId" to uniqueId.toString(), "username" to username, "password" to password, "ip" to ip.orEmpty())
        )

    fun verify(uniqueId: UUID, username: String, password: String, ip: String?): CompletableFuture<HubCredentialResult> =
        post(
            "/api/v1/auth/verify",
            mapOf("uniqueId" to uniqueId.toString(), "username" to username, "password" to password, "ip" to ip.orEmpty())
        )

    fun changePassword(uniqueId: UUID, current: String, replacement: String): CompletableFuture<HubCredentialResult> =
        post(
            "/api/v1/auth/change-password",
            mapOf("uniqueId" to uniqueId.toString(), "currentPassword" to current, "newPassword" to replacement)
        )

    fun beginTotp(uniqueId: UUID, password: String, issuer: String): CompletableFuture<HubTotpResult> =
        post(
            "/api/v1/totp/begin", mapOf("uniqueId" to uniqueId.toString(), "password" to password, "issuer" to issuer),
            HubTotpResult::class.java
        )

    fun confirmTotp(uniqueId: UUID, code: String): CompletableFuture<HubCredentialResult> =
        post("/api/v1/totp/confirm", mapOf("uniqueId" to uniqueId.toString(), "code" to code))

    fun verifyTotp(uniqueId: UUID, code: String): CompletableFuture<HubCredentialResult> =
        post("/api/v1/totp/verify", mapOf("uniqueId" to uniqueId.toString(), "code" to code))

    fun disableTotp(uniqueId: UUID, password: String, code: String): CompletableFuture<HubCredentialResult> =
        post(
            "/api/v1/totp/disable", mapOf("uniqueId" to uniqueId.toString(), "password" to password, "code" to code)
        )

    fun unregister(uniqueId: UUID, password: String): CompletableFuture<HubCredentialResult> =
        post("/api/v1/account/unregister", mapOf("uniqueId" to uniqueId.toString(), "password" to password))

    fun adminUnregister(username: String): CompletableFuture<HubCredentialResult> =
        post("/api/v1/admin/unregister", mapOf("username" to username))

    fun adminChangePassword(username: String, password: String): CompletableFuture<HubCredentialResult> =
        post("/api/v1/admin/change-password", mapOf("username" to username, "password" to password))

    fun forceRegister(username: String, password: String): CompletableFuture<HubCredentialResult> =
        post("/api/v1/admin/register", mapOf("username" to username, "password" to password))

    fun togglePremium(username: String): CompletableFuture<HubCredentialResult> =
        post("/api/v1/admin/toggle-premium", mapOf("username" to username))

    fun premium(username: String): CompletableFuture<HubPremiumResult> =
        post("/api/v1/account/premium", mapOf("username" to username), HubPremiumResult::class.java)

    fun admission(
        username: String, ip: String, online: Int, maxOnline: Int, maxRegistered: Int, excluded: Boolean
    ): CompletableFuture<HubAdmissionResult> = post(
        "/api/v1/admission/check",
        mapOf(
            "username" to username, "ip" to ip, "onlineAccounts" to online,
            "maxOnline" to maxOnline, "maxRegistered" to maxRegistered, "excluded" to excluded
        ), HubAdmissionResult::class.java
    )

    fun publishEvent(event: ClusterEvent): CompletableFuture<Long> =
        post("/api/v1/events/publish", HubEventWire.from(event), HubEventAck::class.java).thenApply { it.sequence }

    fun pollEvents(after: Long): CompletableFuture<HubEventBatch> {
        val path = "/api/v1/events/poll"
        val signed = signature("GET", path, ByteArray(0))
        val request = HttpRequest.newBuilder(URI.create(settings.url + path + "?after=$after"))
            .timeout(Duration.ofSeconds(30))
            .header("X-PnAuth-Client", settings.clientId)
            .header("X-PnAuth-Timestamp", signed.timestamp)
            .header("X-PnAuth-Nonce", signed.nonce)
            .header("X-PnAuth-Signature", signed.value)
            .GET().build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply { response ->
            if (response.statusCode() != 200) throw IllegalStateException("Hub poll failed with HTTP ${response.statusCode()}")
            json.readValue(response.body(), HubEventBatch::class.java)
        }
    }

    private fun post(path: String, value: Any): CompletableFuture<HubCredentialResult> =
        post(path, value, HubCredentialResult::class.java)

    private fun <T> post(path: String, value: Any, responseType: Class<T>): CompletableFuture<T> {
        val body = json.writeValueAsBytes(value)
        val signed = signature("POST", path, body)
        val request = HttpRequest.newBuilder(URI.create(settings.url + path))
            .timeout(Duration.ofMillis(settings.connectTimeoutMillis.toLong().coerceAtLeast(1000) * 2))
            .header("Content-Type", "application/json")
            .header("X-PnAuth-Client", settings.clientId)
            .header("X-PnAuth-Timestamp", signed.timestamp)
            .header("X-PnAuth-Nonce", signed.nonce)
            .header("X-PnAuth-Signature", signed.value)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply { response ->
            if (response.statusCode() != 200) throw IllegalStateException("Hub request failed with HTTP ${response.statusCode()}")
            json.readValue(response.body(), responseType)
        }
    }

    private fun signature(method: String, path: String, body: ByteArray): SignedRequest {
        val timestamp = System.currentTimeMillis().div(1000).toString()
        val nonceBytes = ByteArray(24).also(random::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(body).toHex()
        val canonical = "$method\n$path\n$timestamp\n$nonce\n$digest"
        return SignedRequest(timestamp, nonce, hmac(settings.clientSecret, canonical))
    }

    private fun hmac(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private data class SignedRequest(val timestamp: String, val nonce: String, val value: String)
}

class HubCredentialResult {
    var status: String = ""
    var uniqueId: String? = null
    var username: String? = null
    var premium: Boolean = false
    var totpEnabled: Boolean = false
}

class HubTotpResult {
    var status: String = ""
    var secret: String? = null
    var provisioningUri: String? = null
    var recoveryCodes: List<String> = emptyList()

    fun setup(): TotpSetup? {
        val value = secret ?: return null
        return TotpSetup(value, provisioningUri ?: return null, recoveryCodes)
    }
}

class HubEventAck {
    var sequence: Long = 0
}

class HubPremiumResult { var premium: Boolean = false }
class HubAdmissionResult {
    var allowed: Boolean = false
    var premium: Boolean = false
    var reason: String = "POLICY_DENIED"
}
