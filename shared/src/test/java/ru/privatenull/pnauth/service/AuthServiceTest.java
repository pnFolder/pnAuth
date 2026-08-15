package ru.privatenull.pnauth.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.api.AuthResult;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.api.DialogPreference;
import ru.privatenull.pnauth.api.TotpSetup;
import ru.privatenull.pnauth.command.AuthCommandRequest;
import ru.privatenull.pnauth.command.AuthCommandService;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.storage.AuthRecord;
import ru.privatenull.pnauth.storage.AuthRepository;
import ru.privatenull.pnauth.storage.PasswordHash;
import ru.privatenull.pnauth.config.AuthSettings;
import ru.privatenull.pnauth.config.FeatureSettings;
import ru.privatenull.pnauth.security.TotpService;
import ru.privatenull.pnauth.event.UserAuthenticatedEvent;
import ru.privatenull.pnauth.event.UserLoggedOutEvent;
import ru.privatenull.pnauth.event.PreAuthOperationEvent;
import ru.privatenull.pnauth.extension.AuthOperation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    private final InMemoryRepository repository = new InMemoryRepository();
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(
                repository,
                new AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
                Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
        );
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void registersAuthenticatesAndChangesPassword() {
        UUID uniqueId = UUID.randomUUID();
        assertEquals(AuthStatus.UNREGISTERED, service.onJoin(uniqueId, "Steve").join());
        assertEquals(AuthResult.SUCCESS, service.register(uniqueId, "Steve", "pass", "pass").join());
        assertTrue(service.isAuthenticated(uniqueId));
        assertEquals(AuthResult.SUCCESS, service.changePassword(uniqueId, "pass", "next").join());
        assertEquals(AuthResult.SUCCESS, service.logout(uniqueId).join());
        assertEquals(AuthResult.INVALID_PASSWORD, service.login(uniqueId, "pass").join());
        assertEquals(AuthResult.SUCCESS, service.login(uniqueId, "next").join());
    }

    @Test
    void publishesDomainEventsFromTheServiceApi() {
        UUID uniqueId = UUID.randomUUID();
        java.util.ArrayList<UserAuthenticatedEvent.Cause> authenticated = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger loggedOut = new java.util.concurrent.atomic.AtomicInteger();
        service.events().subscribe(UserAuthenticatedEvent.class, event -> authenticated.add(event.cause()));
        service.events().subscribe(UserLoggedOutEvent.class, event -> loggedOut.incrementAndGet());

        service.onJoin(uniqueId, "EventUser").join();
        service.register(uniqueId, "EventUser", "pass", "pass").join();
        service.logout(uniqueId).join();
        service.login(uniqueId, "pass").join();

        assertEquals(List.of(UserAuthenticatedEvent.Cause.REGISTER,
                UserAuthenticatedEvent.Cause.PASSWORD), authenticated);
        assertEquals(1, loggedOut.get());
    }

    @Test
    void cancellablePreEventPreventsMutation() {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "BlockedUser").join();
        service.events().subscribe(PreAuthOperationEvent.class, event -> {
            if (event.context().operation() == AuthOperation.REGISTER) event.cancel("external policy");
        });

        assertEquals(AuthResult.OPERATION_DENIED,
                service.register(uniqueId, "BlockedUser", "pass", "pass").join());
        assertTrue(repository.findByUniqueId(uniqueId).isEmpty());
        assertEquals(AuthStatus.UNREGISTERED, service.status(uniqueId));
    }

    @Test
    void asynchronousExtensionCanRequireAndApproveExternalVerification() {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "DiscordUser").join();
        service.extensions().register("discord", 100, context -> CompletableFuture.completedFuture(
                context.operation() == AuthOperation.REGISTER
                        ? ru.privatenull.pnauth.extension.AuthPolicyDecision.requireVerification(
                        "discord", "Approve account creation", Duration.ofMinutes(5))
                        : ru.privatenull.pnauth.extension.AuthPolicyDecision.allow()));

        assertEquals(AuthResult.ADDITIONAL_VERIFICATION_REQUIRED,
                service.register(uniqueId, "DiscordUser", "pass", "pass").join());
        var ticket = service.extensions().pending(uniqueId).orElseThrow();
        assertTrue(service.extensions().approve(ticket.id()));
        assertEquals(AuthResult.SUCCESS,
                service.register(uniqueId, "DiscordUser", "pass", "pass").join());
    }

    @Test
    void externalLoginVerificationStartsOnlyAfterValidPassword() {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "LinkedUser").join();
        service.register(uniqueId, "LinkedUser", "pass", "pass").join();
        service.logout(uniqueId).join();
        service.extensions().register("discord", 100, context -> CompletableFuture.completedFuture(
                context.operation() == AuthOperation.LOGIN
                        && context.phase() == ru.privatenull.pnauth.extension.AuthPhase.CREDENTIAL_VERIFIED
                        ? ru.privatenull.pnauth.extension.AuthPolicyDecision.requireVerification(
                        "discord", "Confirm login", Duration.ofMinutes(5))
                        : ru.privatenull.pnauth.extension.AuthPolicyDecision.allow()));

        assertEquals(AuthResult.INVALID_PASSWORD, service.login(uniqueId, "wrong").join());
        assertTrue(service.extensions().pending(uniqueId).isEmpty());
        assertEquals(AuthResult.ADDITIONAL_VERIFICATION_REQUIRED, service.login(uniqueId, "pass").join());
        var ticket = service.extensions().pending(uniqueId).orElseThrow();
        assertEquals(ru.privatenull.pnauth.extension.AuthPhase.CREDENTIAL_VERIFIED,
                new ru.privatenull.pnauth.extension.AuthOperationContext(
                        AuthOperation.LOGIN, ru.privatenull.pnauth.extension.AuthPhase.CREDENTIAL_VERIFIED,
                        uniqueId, "LinkedUser", null, Map.of()).phase());
        assertTrue(service.extensions().approve(ticket.id()));
        for (int attempt = 0; attempt < 100 && !service.isAuthenticated(uniqueId); attempt++) {
            try { Thread.sleep(5); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
        assertTrue(service.isAuthenticated(uniqueId));
    }

    @Test
    void recognizesExistingAccountAfterProxyChangesPlayerUuid() {
        UUID previousUniqueId = UUID.randomUUID();
        UUID currentUniqueId = UUID.randomUUID();
        assertEquals(AuthStatus.UNREGISTERED, service.onJoin(previousUniqueId, "Steve", "127.0.0.1").join());
        assertEquals(AuthResult.SUCCESS,
                service.register(previousUniqueId, "Steve", "pass", "pass").join());
        service.onQuit(previousUniqueId);

        assertEquals(AuthStatus.UNAUTHENTICATED,
                service.onJoin(currentUniqueId, "Steve", "127.0.0.2").join());
        assertEquals(AuthResult.SUCCESS, service.login(currentUniqueId, "pass").join());
        assertTrue(service.isAuthenticated(currentUniqueId));
        assertTrue(repository.findByUniqueId(currentUniqueId).isPresent());
        assertTrue(repository.findByUniqueId(previousUniqueId).isEmpty());
    }

    @Test
    void locksAfterRepeatedInvalidPasswords() {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "Alex").join();
        service.register(uniqueId, "Alex", "pass", "pass").join();
        service.logout(uniqueId).join();

        AuthResult first = service.login(uniqueId, "bad").join();
        AuthResult second = service.login(uniqueId, "bad").join();
        AuthResult third = service.login(uniqueId, "bad").join();
        assertEquals(AuthResult.INVALID_PASSWORD, first);
        assertEquals(AuthResult.INVALID_PASSWORD, second);
        assertEquals(AuthResult.LOCKED_OUT, third);
        assertEquals(AuthResult.LOCKED_OUT, service.login(uniqueId, "pass").join());
    }

    @Test
    void doesNotRestorePasswordSessionFromIpByDefault() {
        UUID originalId = UUID.randomUUID();
        UUID reconnectId = UUID.randomUUID();
        service.onJoin(originalId, "SharedNetwork", "203.0.113.10").join();
        assertEquals(AuthResult.SUCCESS, service.register(originalId, "SharedNetwork", "pass", "pass").join());
        service.onQuit(originalId);

        assertEquals(AuthStatus.UNAUTHENTICATED,
                service.onJoin(reconnectId, "SharedNetwork", "203.0.113.10").join());
    }

    @Test
    void logoutRevokesRestorableIpSession() {
        service.close();
        FeatureSettings defaults = FeatureSettings.defaults();
        FeatureSettings restoreSessions = new FeatureSettings(
                defaults.premiumEnabled(), true, defaults.sessionLifetime(), defaults.authTimeout(),
                defaults.reminderInterval(), defaults.banOnFailedLogin(), defaults.banDuration(),
                defaults.maxOnlineAccountsPerIp(), defaults.maxRegisteredAccountsPerIp(), defaults.excludedIps(),
                defaults.totpEnabled(), defaults.totpMaxAttempts(), defaults.totpLockoutDuration(),
                defaults.totpSetupLifetime(), defaults.totpIssuer(), defaults.recoveryCodesAmount(),
                defaults.repeatPasswordWhenRegister(), defaults.dialogs(), defaults.captcha(),
                defaults.titleEnabled(), defaults.actionBarEnabled()
        );
        service = new AuthService(repository, new AuthSettings(4, 32, 3, Duration.ofMinutes(1), 10_000),
                new TotpService(repository, new byte[32]), restoreSessions,
                Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "SessionUser", "203.0.113.10").join();
        assertEquals(AuthResult.SUCCESS, service.register(uniqueId, "SessionUser", "pass", "pass").join());

        assertEquals(AuthResult.SUCCESS, service.logout(uniqueId).join());
        service.onQuit(uniqueId);

        assertEquals(AuthStatus.UNAUTHENTICATED,
                service.onJoin(uniqueId, "SessionUser", "203.0.113.10").join());
    }

    @Test
    void passwordLockoutFollowsAccountAcrossProxyUuidChanges() {
        UUID originalId = UUID.randomUUID();
        service.onJoin(originalId, "LockUser").join();
        service.register(originalId, "LockUser", "pass", "pass").join();
        service.logout(originalId).join();
        assertEquals(AuthResult.INVALID_PASSWORD, service.login(originalId, "bad").join());
        assertEquals(AuthResult.INVALID_PASSWORD, service.login(originalId, "bad").join());
        service.onQuit(originalId);

        UUID changedId = UUID.randomUUID();
        service.onJoin(changedId, "LockUser").join();
        assertEquals(AuthResult.LOCKED_OUT, service.login(changedId, "bad").join());
        assertEquals(AuthResult.LOCKED_OUT, service.login(changedId, "pass").join());
    }

    @Test
    void forceLoginAuthenticatesSessionStoredUnderCurrentProxyUuid() {
        UUID originalId = UUID.randomUUID();
        UUID changedId = UUID.randomUUID();
        service.onJoin(originalId, "ForceUser").join();
        service.register(originalId, "ForceUser", "pass", "pass").join();
        service.onQuit(originalId);
        service.onJoin(changedId, "ForceUser").join();

        assertEquals(AuthResult.SUCCESS, service.forceLogin("ForceUser").join());
        assertTrue(service.isAuthenticated(changedId));
        assertTrue(repository.findByUniqueId(changedId).isPresent());
    }

    @Test
    void premiumAccountWithTotpMustStillProvideSecondFactor() {
        UUID uniqueId = UUID.randomUUID();
        repository.create(new AuthRecord(
                uniqueId, "premiumtwofactor", "PremiumTwoFactor",
                PasswordHash.legacy("SHA256", "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"),
                1_000L, 1_000L, true, "127.0.0.1", "127.0.0.1", "encrypted-totp", DialogPreference.AUTO
        ));

        assertEquals(AuthStatus.TOTP_PENDING, service.onJoin(uniqueId, "PremiumTwoFactor", "127.0.0.1").join());
        assertFalse(service.isAuthenticated(uniqueId));
    }

    @Test
    void requiresAuthenticatedSessionToDeleteAccount() {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "DeleteGuard").join();
        assertEquals(AuthResult.SUCCESS, service.register(uniqueId, "DeleteGuard", "pass", "pass").join());
        assertEquals(AuthResult.SUCCESS, service.logout(uniqueId).join());

        assertEquals(AuthResult.NOT_AUTHENTICATED, service.unregister(uniqueId, "pass").join());
        assertTrue(repository.findByUniqueId(uniqueId).isPresent());
    }

    @Test
    void upgradesImportedFastHashAfterSuccessfulPasswordLogin() {
        UUID uniqueId = UUID.randomUUID();
        repository.create(new AuthRecord(
                uniqueId, "legacyuser", "LegacyUser",
                PasswordHash.legacy("SHA256", "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"),
                1_000L, null, false, null, null, null, DialogPreference.AUTO
        ));
        service.onJoin(uniqueId, "LegacyUser").join();

        assertEquals(AuthResult.SUCCESS, service.login(uniqueId, "password").join());
        assertEquals("PBKDF2", repository.findByUniqueId(uniqueId).orElseThrow().passwordHash().algorithm());
    }

    @Test
    void fallsBackToCommandsForUnsupportedClients() {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "DialogUser").join();

        assertFalse(service.shouldUseDialog(uniqueId, 770, true));
        assertTrue(service.shouldUseDialog(uniqueId, 771, true));
        assertFalse(service.shouldUseDialog(uniqueId, 771, false));
    }

    @Test
    void storesPlayerDialogPreference() {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "DialogUser").join();
        service.register(uniqueId, "DialogUser", "pass", "pass").join();

        assertEquals(DialogPreference.AUTO, service.dialogPreference(uniqueId));
        assertEquals(AuthResult.DIALOG_PREFERENCE_UPDATED,
                service.setDialogPreference(uniqueId, DialogPreference.DISABLED).join());
        assertEquals(DialogPreference.DISABLED, service.dialogPreference(uniqueId));
        assertFalse(service.shouldUseDialog(uniqueId, 771, true));
        assertTrue(service.shouldUseCommandFallback(uniqueId, 771, true));

        service.setDialogPreference(uniqueId, DialogPreference.ENABLED).join();
        assertTrue(service.shouldUseDialog(uniqueId, 771, true));
    }

    @Test
    void forceRegistersAccountForConsole() {
        assertEquals(AuthResult.SUCCESS, service.forceRegister("ConsoleUser", "pass").join());
        assertEquals(AuthResult.ALREADY_REGISTERED, service.forceRegister("ConsoleUser", "pass").join());
        assertTrue(repository.findByUsername("consoleuser").isPresent());
    }

    @Test
    void verifiesPendingTotpSetupAndClearsItOnLogout() {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "TwoFactorUser").join();
        assertEquals(AuthResult.SUCCESS, service.register(uniqueId, "TwoFactorUser", "pass", "pass").join());

        TotpSetup setup = service.beginTotpSetup(uniqueId, "pass", "pnAuth").join();
        assertFalse(setup.secret().isBlank());
        // This must go through confirmation, not the login-only TOTP verification branch.
        assertEquals(AuthResult.TOTP_INVALID, service.verifyTotp(uniqueId, "not-a-code").join());

        assertEquals(AuthResult.SUCCESS, service.logout(uniqueId).join());
        assertEquals(AuthResult.TOTP_NOT_ENABLED, service.verifyTotp(uniqueId, "not-a-code").join());
    }

    @Test
    void doesNotExposePremiumModeAsASelfServiceCommand() throws Exception {
        UUID uniqueId = UUID.randomUUID();
        service.onJoin(uniqueId, "PremiumUser").join();
        AuthCommandService commands = new AuthCommandService(service, AuthMessages.load("en"));

        List<String> output = commands.execute(new AuthCommandRequest(
                uniqueId, "PremiumUser", "premium", List.of(), permission -> false
        )).toCompletableFuture().join();

        assertEquals("You do not have permission.", output.get(0));
    }

    @Test
    void executesForceRegisterFromConsoleCommand() throws Exception {
        AuthCommandService commands = new AuthCommandService(service, AuthMessages.load("en"));
        List<String> output = commands.execute(new AuthCommandRequest(
                null,
                null,
                "auth",
                List.of("forceregister", "ConsoleUser", "pass"),
                permission -> permission.equals("pnauth.admin.commands.forceregister")
        )).toCompletableFuture().join();

        assertTrue(output.get(0).contains("ConsoleUser"));
        assertTrue(repository.findByUsername("consoleuser").isPresent());
    }

    private static final class InMemoryRepository implements AuthRepository {
        private final Map<UUID, AuthRecord> records = new HashMap<>();

        @Override
        public synchronized Optional<AuthRecord> findByUniqueId(UUID uniqueId) {
            return Optional.ofNullable(records.get(uniqueId));
        }

        @Override
        public synchronized Optional<AuthRecord> findByUsername(String username) {
            return records.values().stream()
                    .filter(record -> record.username().equals(username.toLowerCase(Locale.ROOT)))
                    .findFirst();
        }

        @Override
        public synchronized boolean create(AuthRecord record) {
            if (records.containsKey(record.uniqueId()) || findByUsername(record.username()).isPresent()) {
                return false;
            }
            records.put(record.uniqueId(), record);
            return true;
        }

        @Override
        public synchronized boolean updateUsername(UUID uniqueId, String username) {
            AuthRecord current = records.get(uniqueId);
            if (current == null || records.values().stream().anyMatch(
                    record -> !record.uniqueId().equals(uniqueId) && record.username().equals(username))) {
                return false;
            }
            records.put(uniqueId, new AuthRecord(
                    current.uniqueId(), username, current.passwordHash(), current.registeredAt(), current.lastLoginAt()
            ));
            return true;
        }

        @Override
        public synchronized boolean reassignUniqueId(UUID previousUniqueId, UUID currentUniqueId) {
            AuthRecord current = records.get(previousUniqueId);
            if (current == null || records.containsKey(currentUniqueId)) return false;
            records.remove(previousUniqueId);
            records.put(currentUniqueId, new AuthRecord(
                    currentUniqueId, current.username(), current.realName(), current.passwordHash(),
                    current.registeredAt(), current.lastLoginAt(), current.premium(), current.registeredIp(),
                    current.lastIp(), current.totpSecret(), current.dialogPreference()
            ));
            return true;
        }

        @Override
        public synchronized void updateLastLogin(UUID uniqueId, long timestamp) {
            AuthRecord current = records.get(uniqueId);
            records.put(uniqueId, new AuthRecord(
                    current.uniqueId(), current.username(), current.realName(), current.passwordHash(),
                    current.registeredAt(), timestamp, current.premium(), current.registeredIp(), current.lastIp(),
                    current.totpSecret(), current.dialogPreference()
            ));
        }

        @Override
        public synchronized void updatePassword(UUID uniqueId, PasswordHash passwordHash) {
            AuthRecord current = records.get(uniqueId);
            records.put(uniqueId, new AuthRecord(
                    current.uniqueId(), current.username(), current.realName(), passwordHash,
                    current.registeredAt(), current.lastLoginAt(), current.premium(), current.registeredIp(),
                    current.lastIp(), current.totpSecret(), current.dialogPreference()
            ));
        }

        @Override
        public synchronized void updateLastIp(UUID uniqueId, String ip) {
            AuthRecord current = records.get(uniqueId);
            records.put(uniqueId, new AuthRecord(
                    current.uniqueId(), current.username(), current.realName(), current.passwordHash(),
                    current.registeredAt(), current.lastLoginAt(), current.premium(), current.registeredIp(), ip,
                    current.totpSecret(), current.dialogPreference()
            ));
        }
    }
}
