package ru.privatenull.pnauth.hub

import ru.privatenull.pnauth.config.AuthSettings
import ru.privatenull.pnauth.security.PasswordHasher
import ru.privatenull.pnauth.security.TotpService
import ru.privatenull.pnauth.api.TotpSetup
import ru.privatenull.pnauth.storage.AuthRecord
import ru.privatenull.pnauth.storage.AuthRepository
import java.time.Clock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.nio.charset.StandardCharsets

class HubCredentialService(
    private val repository: AuthRepository,
    private val totp: TotpService,
    private val settings: AuthSettings = AuthSettings.defaults(),
    private val clock: Clock = Clock.systemUTC()
) {
    private val attempts = ConcurrentHashMap<UUID, Attempt>()
    private val pendingTotp = ConcurrentHashMap<UUID, PendingTotp>()

    fun lookup(request: PlayerRequest): CredentialResult {
        val record = find(request) ?: return CredentialResult("NOT_REGISTERED")
        return CredentialResult("REGISTERED", record.uniqueId.toString(), record.realName, record.premium, !record.totpSecret.isNullOrBlank())
    }

    fun register(request: CredentialRequest): CredentialResult {
        val id = parseId(request.uniqueId) ?: return CredentialResult("INVALID_REQUEST")
        if (!settings.isUsernameValid(request.username) || !settings.isPasswordValid(request.password)) {
            return CredentialResult("INVALID_CREDENTIALS")
        }
        val now = clock.millis()
        val record = AuthRecord.registered(
            id, request.username.lowercase(Locale.ROOT), request.username,
            PasswordHasher.hash(request.password, settings), now, request.ip.takeIf { it.isNotBlank() }
        )
        return if (repository.create(record)) CredentialResult("SUCCESS", id.toString(), request.username)
        else CredentialResult("ALREADY_REGISTERED")
    }

    fun verify(request: CredentialRequest): CredentialResult {
        val id = parseId(request.uniqueId) ?: return CredentialResult("INVALID_REQUEST")
        val now = clock.millis()
        val attempt = attempts[id]
        if (attempt != null && attempt.lockedUntil > now) return CredentialResult("LOCKED_OUT")
        val record = find(request) ?: return CredentialResult("NOT_REGISTERED")
        if (!PasswordHasher.matches(request.password, record.passwordHash)) {
            val failures = (attempt?.failures ?: 0) + 1
            attempts[id] = if (failures >= settings.maxLoginAttempts) Attempt(0, now + settings.lockoutDuration.toMillis())
            else Attempt(failures, 0)
            return CredentialResult("INVALID_CREDENTIALS")
        }
        attempts.remove(id)
        if (PasswordHasher.needsRehash(record.passwordHash, settings)) {
            repository.updatePassword(record.uniqueId, PasswordHasher.hash(request.password, settings))
        }
        repository.updateLastLogin(record.uniqueId, now)
        repository.updateLastIp(record.uniqueId, request.ip.takeIf { it.isNotBlank() })
        return CredentialResult(
            if (record.totpSecret.isNullOrBlank()) "SUCCESS" else "TOTP_REQUIRED",
            record.uniqueId.toString(), record.realName, record.premium, !record.totpSecret.isNullOrBlank()
        )
    }

    fun changePassword(request: ChangePasswordRequest): CredentialResult {
        val record = repository.findByUniqueId(parseId(request.uniqueId) ?: return CredentialResult("INVALID_REQUEST")).orElse(null)
            ?: return CredentialResult("NOT_REGISTERED")
        if (!PasswordHasher.matches(request.currentPassword, record.passwordHash)) return CredentialResult("INVALID_CREDENTIALS")
        if (!settings.isPasswordValid(request.newPassword)) return CredentialResult("INVALID_NEW_PASSWORD")
        repository.updatePassword(record.uniqueId, PasswordHasher.hash(request.newPassword, settings))
        repository.updateLastIp(record.uniqueId, null)
        return CredentialResult("SUCCESS", record.uniqueId.toString(), record.realName)
    }

    fun beginTotp(request: BeginTotpRequest): TotpResult {
        val id = parseId(request.uniqueId) ?: return TotpResult("INVALID_REQUEST")
        val record = repository.findByUniqueId(id).orElse(null) ?: return TotpResult("NOT_REGISTERED")
        if (!record.totpSecret.isNullOrBlank()) return TotpResult("TOTP_ALREADY_ENABLED")
        if (!PasswordHasher.matches(request.password, record.passwordHash)) return TotpResult("INVALID_CREDENTIALS")
        val secret = totp.generateSecret()
        val setup = TotpSetup(
            secret, totp.provisioningUri(request.issuer.ifBlank { "pnAuth" }, record.realName, secret),
            totp.generateRecoveryCodes(16)
        )
        pendingTotp[id] = PendingTotp(setup, clock.millis() + 300_000)
        return TotpResult("SUCCESS", setup.secret, setup.provisioningUri, setup.recoveryCodes)
    }

    fun confirmTotp(request: TotpCodeRequest): CredentialResult {
        val id = parseId(request.uniqueId) ?: return CredentialResult("INVALID_REQUEST")
        val pending = pendingTotp[id] ?: return CredentialResult("TOTP_SETUP_REQUIRED")
        if (pending.expiresAt < clock.millis()) {
            pendingTotp.remove(id, pending)
            return CredentialResult("TOTP_SETUP_REQUIRED")
        }
        if (!totp.verify(pending.setup.secret, request.code)) return CredentialResult("TOTP_INVALID")
        totp.replaceTotpData(id, totp.encrypt(pending.setup.secret), pending.setup.recoveryCodes)
        pendingTotp.remove(id, pending)
        return CredentialResult("SUCCESS", id.toString())
    }

    fun verifyTotp(request: TotpCodeRequest): CredentialResult {
        val id = parseId(request.uniqueId) ?: return CredentialResult("INVALID_REQUEST")
        val record = repository.findByUniqueId(id).orElse(null) ?: return CredentialResult("NOT_REGISTERED")
        val encrypted = record.totpSecret ?: return CredentialResult("TOTP_NOT_ENABLED")
        val valid = runCatching { totp.verify(totp.decrypt(encrypted), request.code) }.getOrDefault(false) ||
            totp.consumeRecoveryCode(id, request.code)
        return CredentialResult(if (valid) "SUCCESS" else "TOTP_INVALID", id.toString(), record.realName)
    }

    fun disableTotp(request: DisableTotpRequest): CredentialResult {
        val id = parseId(request.uniqueId) ?: return CredentialResult("INVALID_REQUEST")
        val record = repository.findByUniqueId(id).orElse(null) ?: return CredentialResult("NOT_REGISTERED")
        if (!PasswordHasher.matches(request.password, record.passwordHash)) return CredentialResult("INVALID_CREDENTIALS")
        val verified = verifyTotp(TotpCodeRequest().apply { uniqueId = request.uniqueId; code = request.code })
        if (verified.status != "SUCCESS") return verified
        totp.clearTotpData(id)
        return CredentialResult("SUCCESS", id.toString(), record.realName)
    }

    fun unregister(request: UnregisterRequest): CredentialResult {
        val id = parseId(request.uniqueId) ?: return CredentialResult("INVALID_REQUEST")
        val record = repository.findByUniqueId(id).orElse(null) ?: return CredentialResult("NOT_REGISTERED")
        if (!PasswordHasher.matches(request.password, record.passwordHash)) return CredentialResult("INVALID_CREDENTIALS")
        repository.deleteByUniqueId(id)
        attempts.remove(id)
        pendingTotp.remove(id)
        return CredentialResult("SUCCESS", id.toString(), record.realName)
    }

    fun adminUnregister(request: UsernameRequest): CredentialResult {
        val record = repository.findByUsername(request.username.lowercase(Locale.ROOT)).orElse(null)
            ?: return CredentialResult("PLAYER_NOT_FOUND")
        repository.deleteByUniqueId(record.uniqueId)
        attempts.remove(record.uniqueId)
        pendingTotp.remove(record.uniqueId)
        return CredentialResult("SUCCESS", record.uniqueId.toString(), record.realName)
    }

    fun adminChangePassword(request: AdminPasswordRequest): CredentialResult {
        if (!settings.isPasswordValid(request.password)) return CredentialResult("INVALID_NEW_PASSWORD")
        val record = repository.findByUsername(request.username.lowercase(Locale.ROOT)).orElse(null)
            ?: return CredentialResult("PLAYER_NOT_FOUND")
        repository.updatePassword(record.uniqueId, PasswordHasher.hash(request.password, settings))
        repository.updateLastIp(record.uniqueId, null)
        return CredentialResult("SUCCESS", record.uniqueId.toString(), record.realName)
    }

    fun forceRegister(request: AdminPasswordRequest): CredentialResult {
        if (!settings.isUsernameValid(request.username) || !settings.isPasswordValid(request.password)) {
            return CredentialResult("INVALID_REQUEST")
        }
        val id = UUID.nameUUIDFromBytes("OfflinePlayer:${request.username}".toByteArray(StandardCharsets.UTF_8))
        val record = AuthRecord.registered(
            id, request.username.lowercase(Locale.ROOT), request.username,
            PasswordHasher.hash(request.password, settings), clock.millis(), null
        )
        return if (repository.create(record)) CredentialResult("SUCCESS", id.toString(), request.username)
        else CredentialResult("ALREADY_REGISTERED")
    }

    fun togglePremium(request: UsernameRequest): CredentialResult {
        val record = repository.findByUsername(request.username.lowercase(Locale.ROOT)).orElse(null)
            ?: return CredentialResult("PLAYER_NOT_FOUND")
        repository.updatePremium(record.uniqueId, !record.premium)
        return CredentialResult("SUCCESS", record.uniqueId.toString(), record.realName, !record.premium, !record.totpSecret.isNullOrBlank())
    }

    fun premium(request: UsernameRequest): PremiumResult {
        val record = repository.findByUsername(request.username.lowercase(Locale.ROOT)).orElse(null)
        return PremiumResult(record?.premium ?: false)
    }

    fun admission(request: AdmissionRequest): AdmissionResult {
        if (!request.excluded && request.onlineAccounts >= request.maxOnline) {
            return AdmissionResult(false, false, "ONLINE_IP_LIMIT")
        }
        val record = repository.findByUsername(request.username.lowercase(Locale.ROOT)).orElse(null)
        if (record == null && !request.excluded && repository.countRegisteredIp(request.ip) >= request.maxRegistered) {
            return AdmissionResult(false, false, "REGISTERED_IP_LIMIT")
        }
        return AdmissionResult(true, record?.premium ?: false, "ALLOWED")
    }

    private fun find(request: PlayerRequest): AuthRecord? {
        val id = parseId(request.uniqueId)
        if (id != null) repository.findByUniqueId(id).orElse(null)?.let { return it }
        return request.username.takeIf { it.isNotBlank() }
            ?.let { repository.findByUsername(it.lowercase(Locale.ROOT)).orElse(null) }
    }

    private fun parseId(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()
    private data class Attempt(val failures: Int, val lockedUntil: Long)
    private data class PendingTotp(val setup: TotpSetup, val expiresAt: Long)
}

open class PlayerRequest {
    var uniqueId: String = ""
    var username: String = ""
}

class CredentialRequest : PlayerRequest() {
    var password: String = ""
    var ip: String = ""
}

class ChangePasswordRequest {
    var uniqueId: String = ""
    var currentPassword: String = ""
    var newPassword: String = ""
}

class BeginTotpRequest {
    var uniqueId: String = ""
    var password: String = ""
    var issuer: String = ""
}

open class TotpCodeRequest {
    var uniqueId: String = ""
    var code: String = ""
}

class DisableTotpRequest : TotpCodeRequest() {
    var password: String = ""
}

class UnregisterRequest {
    var uniqueId: String = ""
    var password: String = ""
}

open class UsernameRequest {
    var username: String = ""
}

class AdminPasswordRequest : UsernameRequest() {
    var password: String = ""
}

class AdmissionRequest : UsernameRequest() {
    var ip: String = ""
    var onlineAccounts: Int = 0
    var maxOnline: Int = 10
    var maxRegistered: Int = 10
    var excluded: Boolean = false
}

data class CredentialResult(
    val status: String,
    val uniqueId: String? = null,
    val username: String? = null,
    val premium: Boolean = false,
    val totpEnabled: Boolean = false
)

data class TotpResult(
    val status: String,
    val secret: String? = null,
    val provisioningUri: String? = null,
    val recoveryCodes: List<String> = emptyList()
)

data class PremiumResult(val premium: Boolean)
data class AdmissionResult(val allowed: Boolean, val premium: Boolean, val reason: String)
