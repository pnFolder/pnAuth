package ru.privatenull.pnauth.storage

import ru.privatenull.pnauth.api.AuthUser
import ru.privatenull.pnauth.api.DialogPreference
import java.time.Instant
import java.util.UUID

data class AuthRecord @JvmOverloads constructor(
    val uniqueId: UUID,
    val username: String,
    val realName: String,
    val passwordHash: PasswordHash,
    val registeredAt: Long,
    val lastLoginAt: Long?,
    val premium: Boolean = false,
    val registeredIp: String? = null,
    val lastIp: String? = null,
    val totpSecret: String? = null,
    val dialogPreference: DialogPreference = DialogPreference.AUTO
) {
    constructor(uniqueId: UUID, username: String, passwordHash: PasswordHash, registeredAt: Long, lastLoginAt: Long?) : this(
        uniqueId, username, username, passwordHash, registeredAt, lastLoginAt, false, null, null, null,
        DialogPreference.AUTO
    )

    fun uniqueId(): UUID = uniqueId
    fun username(): String = username
    fun realName(): String = realName
    fun passwordHash(): PasswordHash = passwordHash
    fun registeredAt(): Long = registeredAt
    fun lastLoginAt(): Long? = lastLoginAt
    fun premium(): Boolean = premium
    fun registeredIp(): String? = registeredIp
    fun lastIp(): String? = lastIp
    fun totpSecret(): String? = totpSecret
    fun dialogPreference(): DialogPreference = dialogPreference

    fun toApiUser(): AuthUser {
        return AuthUser(
            uniqueId,
            username,
            Instant.ofEpochMilli(registeredAt),
            lastLoginAt?.let { Instant.ofEpochMilli(it) },
            premium,
            !totpSecret.isNullOrBlank(),
            lastIp,
            dialogPreference
        )
    }

    fun withUsername(username: String, realName: String): AuthRecord {
        return AuthRecord(
            uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
            registeredIp, lastIp, totpSecret, dialogPreference
        )
    }

    fun withPasswordHash(passwordHash: PasswordHash): AuthRecord {
        return AuthRecord(
            uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
            registeredIp, lastIp, totpSecret, dialogPreference
        )
    }

    fun withLogin(timestamp: Long, ip: String?): AuthRecord {
        return AuthRecord(
            uniqueId, username, realName, passwordHash, registeredAt, timestamp, premium,
            registeredIp, ip, totpSecret, dialogPreference
        )
    }

    fun withPremium(premium: Boolean): AuthRecord {
        return AuthRecord(
            uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
            registeredIp, lastIp, totpSecret, dialogPreference
        )
    }

    fun withTotpSecret(totpSecret: String?): AuthRecord {
        return AuthRecord(
            uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
            registeredIp, lastIp, totpSecret, dialogPreference
        )
    }

    fun withDialogPreference(preference: DialogPreference): AuthRecord {
        return AuthRecord(
            uniqueId, username, realName, passwordHash, registeredAt, lastLoginAt, premium,
            registeredIp, lastIp, totpSecret, preference
        )
    }

    companion object {
        @JvmStatic
        fun registered(
            uniqueId: UUID, username: String, realName: String, passwordHash: PasswordHash, timestamp: Long, ip: String?
        ): AuthRecord {
            return AuthRecord(
                uniqueId, username, realName, passwordHash, timestamp, timestamp,
                false, ip, ip, null, DialogPreference.AUTO
            )
        }
    }
}
