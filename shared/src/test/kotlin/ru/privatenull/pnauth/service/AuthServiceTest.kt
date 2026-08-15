package ru.privatenull.pnauth.service

import at.favre.lib.crypto.bcrypt.BCrypt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.api.AdmissionDecision
import ru.privatenull.pnauth.api.AuthResult
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.api.DialogPreference
import ru.privatenull.pnauth.command.AuthCommandRequest
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.config.AuthSettings
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.event.SimpleAuthEventBus
import ru.privatenull.pnauth.extension.DefaultAuthExtensionRegistry
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.security.TotpService
import ru.privatenull.pnauth.storage.AuthRecord
import ru.privatenull.pnauth.storage.AuthRepository
import ru.privatenull.pnauth.storage.PasswordHash
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.HashMap
import java.util.Locale
import java.util.Optional
import java.util.UUID

class AuthServiceTest {
    private lateinit var repository: InMemoryRepository
    private lateinit var service: AuthService

    @BeforeEach
    fun setUp() {
        repository = InMemoryRepository()
        service = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            TotpService(repository, ByteArray(32)), FeatureSettings.defaults(),
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )
    }

    @Test
    fun registersAndAuthenticatesUser() {
        val uniqueId = UUID.randomUUID()
        assertEquals(AuthStatus.NOT_LOADED, service.status(uniqueId))

        service.onJoin(uniqueId, "TestUser").join()
        assertEquals(AuthStatus.UNREGISTERED, service.status(uniqueId))

        val regResult = service.register(uniqueId, "TestUser", "password123", "password123").join()
        assertEquals(AuthResult.SUCCESS, regResult)
        assertEquals(AuthStatus.AUTHENTICATED, service.status(uniqueId))
        assertTrue(service.isAuthenticated(uniqueId))

        service.onQuit(uniqueId)
        assertEquals(AuthStatus.NOT_LOADED, service.status(uniqueId))
        assertFalse(service.isAuthenticated(uniqueId))

        service.onJoin(uniqueId, "TestUser").join()
        assertEquals(AuthStatus.UNAUTHENTICATED, service.status(uniqueId))

        val loginResult = service.login(uniqueId, "password123").join()
        assertEquals(AuthResult.SUCCESS, loginResult)
        assertEquals(AuthStatus.AUTHENTICATED, service.status(uniqueId))
    }

    @Test
    fun rejectsRegistrationMismatch() {
        val uniqueId = UUID.randomUUID()
        service.onJoin(uniqueId, "TestUser").join()
        val result = service.register(uniqueId, "TestUser", "password123", "mismatch").join()
        assertEquals(AuthResult.PASSWORDS_DO_NOT_MATCH, result)
        assertEquals(AuthStatus.UNREGISTERED, service.status(uniqueId))
    }

    @Test
    fun locksOutUserAfterMaxFailedAttempts() {
        val uniqueId = UUID.randomUUID()
        service.onJoin(uniqueId, "LockoutUser").join()
        service.register(uniqueId, "LockoutUser", "correct", "correct").join()

        service.onQuit(uniqueId)
        service.onJoin(uniqueId, "LockoutUser").join()

        for (i in 0 until 2) {
            assertEquals(AuthResult.INVALID_PASSWORD, service.login(uniqueId, "wrong").join())
        }

        assertEquals(AuthResult.LOCKED_OUT, service.login(uniqueId, "wrong").join())
        assertEquals(AuthResult.LOCKED_OUT, service.login(uniqueId, "correct").join())
    }

    @Test
    fun changesPasswordWhenAuthenticated() {
        val uniqueId = UUID.randomUUID()
        service.onJoin(uniqueId, "User").join()
        service.register(uniqueId, "User", "oldPass", "oldPass").join()

        assertEquals(AuthResult.SUCCESS, service.changePassword(uniqueId, "oldPass", "newPass").join())

        service.onQuit(uniqueId)
        service.onJoin(uniqueId, "User").join()

        assertEquals(AuthResult.INVALID_PASSWORD, service.login(uniqueId, "oldPass").join())
        assertEquals(AuthResult.SUCCESS, service.login(uniqueId, "newPass").join())
    }

    @Test
    fun restoresSessionWithinConfiguredLifetime() {
        val restoreSettings = restoreSessionSettings()
        val firstService = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            TotpService(repository, ByteArray(32)), restoreSettings,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )
        val uniqueId = UUID.randomUUID()
        firstService.onJoin(uniqueId, "SessionUser", "203.0.113.10").join()
        assertEquals(AuthResult.SUCCESS, firstService.register(uniqueId, "SessionUser", "pass", "pass").join())

        firstService.onQuit(uniqueId)

        val clockPlus30s = Clock.fixed(Instant.ofEpochMilli(31_000), ZoneOffset.UTC)
        val secondService = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            TotpService(repository, ByteArray(32)), restoreSettings, clockPlus30s,
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )

        secondService.onJoin(uniqueId, "SessionUser", "203.0.113.10").join()
        assertEquals(AuthStatus.AUTHENTICATED, secondService.status(uniqueId))
        assertTrue(secondService.isAuthenticated(uniqueId))
    }

    @Test
    fun doesNotRestoreSessionWhenIpDiffers() {
        val restoreSettings = restoreSessionSettings()
        val firstService = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            TotpService(repository, ByteArray(32)), restoreSettings,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )
        val uniqueId = UUID.randomUUID()
        firstService.onJoin(uniqueId, "SessionUser", "203.0.113.10").join()
        assertEquals(AuthResult.SUCCESS, firstService.register(uniqueId, "SessionUser", "pass", "pass").join())

        firstService.onQuit(uniqueId)
        firstService.onJoin(uniqueId, "SessionUser", "203.0.113.11").join()

        assertEquals(AuthStatus.UNAUTHENTICATED, firstService.status(uniqueId))
        assertFalse(firstService.isAuthenticated(uniqueId))
    }

    @Test
    fun restoresSessionAcrossMojangAndOfflineUuidForSameUsername() {
        val restoreSettings = restoreSessionSettings()
        val firstService = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            TotpService(repository, ByteArray(32)), restoreSettings,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )
        val offlineId = UUID.nameUUIDFromBytes("OfflinePlayer:SameUser".toByteArray(Charsets.UTF_8))
        val mojangId = UUID.randomUUID()

        firstService.onJoin(offlineId, "SameUser", "198.51.100.5").join()
        assertEquals(AuthResult.SUCCESS, firstService.register(offlineId, "SameUser", "secret123", "secret123").join())
        firstService.onQuit(offlineId)

        val status = firstService.onJoin(mojangId, "SameUser", "198.51.100.5").join()
        assertEquals(AuthStatus.AUTHENTICATED, status)
        assertEquals(AuthStatus.AUTHENTICATED, firstService.status(mojangId))
        assertTrue(repository.findByUniqueId(mojangId).isPresent)
        assertFalse(repository.findByUniqueId(offlineId).isPresent)
    }

    @Test
    fun enforcesSingleSessionPerAccountAcrossRejoins() {
        val restoreSettings = restoreSessionSettings()
        val sessionService = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            TotpService(repository, ByteArray(32)), restoreSettings,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )
        val uniqueId = UUID.randomUUID()
        sessionService.onJoin(uniqueId, "UserA", "1.1.1.1").join()
        sessionService.register(uniqueId, "UserA", "pass", "pass").join()

        sessionService.onJoin(uniqueId, "UserA", "1.1.1.1").join()
        assertEquals(AuthStatus.AUTHENTICATED, sessionService.status(uniqueId))
    }

    @Test
    fun blocksRegistrationWhenIpLimitExceeded() {
        val defaults = FeatureSettings.defaults()
        val limitSettings = FeatureSettings(
            defaults.premiumEnabled, defaults.restoreSessionOnSameIp, defaults.sessionLifetime, defaults.authTimeout,
            defaults.reminderInterval, defaults.banOnFailedLogin, defaults.banDuration,
            defaults.maxOnlineAccountsPerIp, 2, defaults.excludedIps,
            defaults.totpEnabled, defaults.totpMaxAttempts, defaults.totpLockoutDuration,
            defaults.totpSetupLifetime, defaults.totpIssuer, defaults.recoveryCodesAmount,
            defaults.repeatPasswordWhenRegister, defaults.dialogs, defaults.captcha,
            defaults.titleEnabled, defaults.actionBarEnabled
        )
        val limitedService = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            TotpService(repository, ByteArray(32)), limitSettings,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )
        for (i in 0 until 2) {
            val uid = UUID.randomUUID()
            limitedService.onJoin(uid, "User$i", "198.51.100.20").join()
            assertEquals(AuthResult.SUCCESS, limitedService.register(uid, "User$i", "pass", "pass").join())
        }

        val admission = limitedService.checkAdmission("UserBlocked", "198.51.100.20", 0).join()
        assertFalse(admission.allowed)
        assertEquals(AdmissionDecision.Reason.REGISTERED_IP_LIMIT, admission.reason)
    }

    @Test
    fun unregistersUserAndClearsState() {
        val uniqueId = UUID.randomUUID()
        service.onJoin(uniqueId, "UserToDel").join()
        service.register(uniqueId, "UserToDel", "pass", "pass").join()

        assertEquals(AuthResult.SUCCESS, service.unregister(uniqueId, "pass").join())
        assertEquals(AuthStatus.NOT_LOADED, service.status(uniqueId))
        assertFalse(repository.findByUsername("usertodel").isPresent)
    }

    @Test
    fun rejectsOperationsWithInvalidInput() {
        val uniqueId = UUID.randomUUID()
        service.onJoin(uniqueId, "ShortUser").join()
        assertEquals(AuthResult.INVALID_PASSWORD_FORMAT, service.register(uniqueId, "ShortUser", "12", "12").join())

        val longPass = "a".repeat(33)
        assertEquals(AuthResult.INVALID_PASSWORD_FORMAT, service.register(uniqueId, "ShortUser", longPass, longPass).join())
    }

    @Test
    fun respectsLegacyBcryptHashDuringLogin() {
        val uniqueId = UUID.randomUUID()
        val bcryptHashString = BCrypt.withDefaults().hashToString(10, "legacyPass".toCharArray())
        val legacyHash = PasswordHash("BCRYPT", "", bcryptHashString, 10)
        repository.create(
            AuthRecord(
                uniqueId, "legacyuser", "LegacyUser", legacyHash, 1_000, 1_000,
                false, "127.0.0.1", "127.0.0.1", null, DialogPreference.AUTO
            )
        )

        service.onJoin(uniqueId, "LegacyUser").join()
        assertEquals(AuthResult.SUCCESS, service.login(uniqueId, "legacyPass").join())

        val updatedRecord = repository.findByUniqueId(uniqueId).orElseThrow()
        assertNotEquals("BCRYPT", updatedRecord.passwordHash.algorithm)
    }

    @Test
    fun logoutRevokesRestorableIpSession() {
        val restoreSessions = restoreSessionSettings()
        val totpService = TotpService(repository, ByteArray(32))
        service = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            totpService, restoreSessions,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )
        val uniqueId = UUID.randomUUID()
        service.onJoin(uniqueId, "SessionUser", "203.0.113.10").join()
        assertEquals(AuthResult.SUCCESS, service.register(uniqueId, "SessionUser", "pass", "pass").join())

        assertEquals(AuthResult.SUCCESS, service.logout(uniqueId).join())
        service.onQuit(uniqueId)

        val clockPlus30s = Clock.fixed(Instant.ofEpochMilli(31_000), ZoneOffset.UTC)
        val secondService = AuthService(
            repository, AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
            totpService, restoreSessions, clockPlus30s,
            SimpleAuthEventBus(), DefaultAuthExtensionRegistry()
        )

        secondService.onJoin(uniqueId, "SessionUser", "203.0.113.10").join()
        assertEquals(AuthStatus.UNAUTHENTICATED, secondService.status(uniqueId))
        assertFalse(secondService.isAuthenticated(uniqueId))
    }

    @Test
    fun verifiesPendingTotpSetupAndClearsItOnLogout() {
        val uniqueId = UUID.randomUUID()
        service.onJoin(uniqueId, "TwoFactorUser").join()
        assertEquals(AuthResult.SUCCESS, service.register(uniqueId, "TwoFactorUser", "pass", "pass").join())

        val setup = service.beginTotpSetup(uniqueId, "pass", "pnAuth").join()
        assertFalse(setup.secret.isBlank())
        assertEquals(AuthResult.TOTP_INVALID, service.verifyTotp(uniqueId, "not-a-code").join())

        assertEquals(AuthResult.SUCCESS, service.logout(uniqueId).join())
        assertEquals(AuthResult.TOTP_NOT_ENABLED, service.verifyTotp(uniqueId, "not-a-code").join())
    }

    @Test
    fun doesNotExposePremiumModeAsASelfServiceCommand() {
        val uniqueId = UUID.randomUUID()
        service.onJoin(uniqueId, "PremiumUser").join()
        val commands = AuthCommandService(service, AuthMessages.load("en"))

        val output = commands.execute(
            AuthCommandRequest(
                uniqueId, "PremiumUser", "premium", emptyList()
            ) { false }
        ).toCompletableFuture().join()

        assertEquals("You do not have permission.", output[0])
    }

    @Test
    fun executesForceRegisterFromConsoleCommand() {
        val commands = AuthCommandService(service, AuthMessages.load("en"))
        val output = commands.execute(
            AuthCommandRequest(
                null,
                null,
                "auth",
                listOf("forceregister", "ConsoleUser", "pass")
            ) { permission -> permission == "pnauth.admin.commands.forceregister" }
        ).toCompletableFuture().join()

        assertTrue(output[0].contains("ConsoleUser"))
        assertTrue(repository.findByUsername("consoleuser").isPresent)
    }

    private fun restoreSessionSettings(): FeatureSettings {
        val defaults = FeatureSettings.defaults()
        return FeatureSettings(
            defaults.premiumEnabled, true, defaults.sessionLifetime, defaults.authTimeout,
            defaults.reminderInterval, defaults.banOnFailedLogin, defaults.banDuration,
            defaults.maxOnlineAccountsPerIp, defaults.maxRegisteredAccountsPerIp, defaults.excludedIps,
            defaults.totpEnabled, defaults.totpMaxAttempts, defaults.totpLockoutDuration,
            defaults.totpSetupLifetime, defaults.totpIssuer, defaults.recoveryCodesAmount,
            defaults.repeatPasswordWhenRegister, defaults.dialogs, defaults.captcha,
            defaults.titleEnabled, defaults.actionBarEnabled
        )
    }

    private class InMemoryRepository : AuthRepository {
        private val records = HashMap<UUID, AuthRecord>()

        @Synchronized
        override fun findByUniqueId(uniqueId: UUID): Optional<AuthRecord> {
            return Optional.ofNullable(records[uniqueId])
        }

        @Synchronized
        override fun findByUsername(username: String): Optional<AuthRecord> {
            return Optional.ofNullable(records.values.firstOrNull { record ->
                record.username.equals(username.lowercase(Locale.ROOT), ignoreCase = true)
            })
        }

        @Synchronized
        override fun countRegisteredIp(ip: String): Long {
            return records.values.count { record -> ip.equals(record.registeredIp, ignoreCase = true) }.toLong()
        }

        @Synchronized
        override fun create(record: AuthRecord): Boolean {
            if (records.containsKey(record.uniqueId) || findByUsername(record.username).isPresent) {
                return false
            }
            records[record.uniqueId] = record
            return true
        }

        @Synchronized
        override fun updateUsername(uniqueId: UUID, username: String): Boolean {
            val current = records[uniqueId] ?: return false
            if (records.values.any { record -> record.uniqueId != uniqueId && record.username.equals(username, ignoreCase = true) }) {
                return false
            }
            records[uniqueId] = AuthRecord(
                current.uniqueId, username, current.passwordHash, current.registeredAt, current.lastLoginAt
            )
            return true
        }

        @Synchronized
        override fun reassignUniqueId(previousUniqueId: UUID, currentUniqueId: UUID): Boolean {
            val current = records[previousUniqueId] ?: return false
            if (records.containsKey(currentUniqueId)) return false
            records.remove(previousUniqueId)
            records[currentUniqueId] = AuthRecord(
                currentUniqueId, current.username, current.realName, current.passwordHash,
                current.registeredAt, current.lastLoginAt, current.premium, current.registeredIp,
                current.lastIp, current.totpSecret, current.dialogPreference
            )
            return true
        }

        @Synchronized
        override fun updateLastLogin(uniqueId: UUID, timestamp: Long) {
            val current = records[uniqueId] ?: return
            records[uniqueId] = AuthRecord(
                current.uniqueId, current.username, current.realName, current.passwordHash,
                current.registeredAt, timestamp, current.premium, current.registeredIp, current.lastIp,
                current.totpSecret, current.dialogPreference
            )
        }

        @Synchronized
        override fun updatePassword(uniqueId: UUID, passwordHash: PasswordHash) {
            val current = records[uniqueId] ?: return
            records[uniqueId] = AuthRecord(
                current.uniqueId, current.username, current.realName, passwordHash,
                current.registeredAt, current.lastLoginAt, current.premium, current.registeredIp,
                current.lastIp, current.totpSecret, current.dialogPreference
            )
        }

        @Synchronized
        override fun updateLastIp(uniqueId: UUID, ip: String?) {
            val current = records[uniqueId] ?: return
            records[uniqueId] = AuthRecord(
                current.uniqueId, current.username, current.realName, current.passwordHash,
                current.registeredAt, current.lastLoginAt, current.premium, current.registeredIp, ip,
                current.totpSecret, current.dialogPreference
            )
        }

        @Synchronized
        override fun deleteByUniqueId(uniqueId: UUID) {
            records.remove(uniqueId)
        }
    }
}
