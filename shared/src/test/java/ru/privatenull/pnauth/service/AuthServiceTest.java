package ru.privatenull.pnauth.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.api.AuthResult;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.api.DialogPreference;
import ru.privatenull.pnauth.command.AuthCommandRequest;
import ru.privatenull.pnauth.command.AuthCommandService;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.storage.AuthRecord;
import ru.privatenull.pnauth.storage.AuthRepository;
import ru.privatenull.pnauth.storage.PasswordHash;
import ru.privatenull.pnauth.config.AuthSettings;

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
        public synchronized void updateLastLogin(UUID uniqueId, long timestamp) {
            AuthRecord current = records.get(uniqueId);
            records.put(uniqueId, new AuthRecord(
                    current.uniqueId(), current.username(), current.passwordHash(), current.registeredAt(), timestamp
            ));
        }

        @Override
        public synchronized void updatePassword(UUID uniqueId, PasswordHash passwordHash) {
            AuthRecord current = records.get(uniqueId);
            records.put(uniqueId, new AuthRecord(
                    current.uniqueId(), current.username(), passwordHash, current.registeredAt(), current.lastLoginAt()
            ));
        }
    }
}
