package ru.privatenull.pnauth.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.storage.AuthRecord
import ru.privatenull.pnauth.storage.AuthRepository
import java.util.Optional
import java.util.UUID

class TotpServiceTest {
    @Test
    fun encryptsSecretsAndCreatesProvisioningData() {
        val key = ByteArray(32)
        val service = TotpService(EmptyRepository(), key)
        val secret = service.generateSecret()
        val encrypted = service.encrypt(secret)

        assertEquals(secret, service.decrypt(encrypted))
        assertTrue(service.provisioningUri("Server", "Steve", secret).startsWith("otpauth://totp/"))
        assertEquals(20, service.generateRecoveryCodes(20).size)
    }

    private class EmptyRepository : AuthRepository {
        override fun findByUniqueId(uniqueId: UUID): Optional<AuthRecord> = Optional.empty()
        override fun findByUsername(username: String): Optional<AuthRecord> = Optional.empty()
        override fun create(record: AuthRecord): Boolean = false
        override fun updatePassword(uniqueId: UUID, passwordHash: ru.privatenull.pnauth.storage.PasswordHash) {}
        override fun updateLastLogin(uniqueId: UUID, timestamp: Long) {}
        override fun updateUsername(uniqueId: UUID, username: String): Boolean = false
    }
}
