package ru.privatenull.pnauth.storage

import ru.privatenull.pnauth.api.DialogPreference
import java.util.Optional
import java.util.UUID

@JvmDefaultWithCompatibility
interface AuthRepository : AutoCloseable {
    fun findByUniqueId(uniqueId: UUID): Optional<AuthRecord>
    fun findByUsername(username: String): Optional<AuthRecord>
    fun create(record: AuthRecord): Boolean
    fun updateUsername(uniqueId: UUID, username: String): Boolean

    /** Moves an account to the UUID currently assigned by the proxy. */
    fun reassignUniqueId(previousUniqueId: UUID, currentUniqueId: UUID): Boolean {
        return previousUniqueId == currentUniqueId
    }

    fun updateLastLogin(uniqueId: UUID, timestamp: Long)
    fun updatePassword(uniqueId: UUID, passwordHash: PasswordHash)

    fun updateLastIp(uniqueId: UUID, ip: String?) {}
    fun updatePremium(uniqueId: UUID, premium: Boolean) {}
    fun updateTotpSecret(uniqueId: UUID, encryptedSecret: String?) {}
    fun updateDialogPreference(uniqueId: UUID, preference: DialogPreference) {}

    fun countRegisteredIp(ip: String): Long = 0
    fun findAll(): List<AuthRecord> = emptyList()
    fun deleteByUniqueId(uniqueId: UUID) {}
    fun clearRecoveryCodes(uniqueId: UUID) {}
    fun addRecoveryCode(uniqueId: UUID, codeHash: String) {}
    fun consumeRecoveryCode(uniqueId: UUID, codeHash: String): Boolean = false

    /** Replaces the encrypted TOTP secret and recovery-code hashes as one logical operation. */
    fun replaceTotpData(uniqueId: UUID, encryptedSecret: String, recoveryCodeHashes: List<String>) {
        updateTotpSecret(uniqueId, encryptedSecret)
        clearRecoveryCodes(uniqueId)
        recoveryCodeHashes.forEach { thisCodeHash -> addRecoveryCode(uniqueId, thisCodeHash) }
    }

    /** Removes the encrypted TOTP secret and all recovery-code hashes as one logical operation. */
    fun clearTotpData(uniqueId: UUID) {
        updateTotpSecret(uniqueId, null)
        clearRecoveryCodes(uniqueId)
    }

    override fun close() {}
}
