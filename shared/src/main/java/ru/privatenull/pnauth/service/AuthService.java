package ru.privatenull.pnauth.service;

import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AdmissionDecision;
import ru.privatenull.pnauth.api.AuthResult;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.api.AuthUser;
import ru.privatenull.pnauth.api.DialogPreference;
import ru.privatenull.pnauth.api.TotpSetup;
import ru.privatenull.pnauth.storage.AuthRecord;
import ru.privatenull.pnauth.storage.AuthRepository;
import ru.privatenull.pnauth.storage.PasswordHash;
import ru.privatenull.pnauth.config.AuthSettings;
import ru.privatenull.pnauth.config.FeatureSettings;
import ru.privatenull.pnauth.security.IpBanStore;
import ru.privatenull.pnauth.security.PasswordHasher;
import ru.privatenull.pnauth.security.TotpService;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class AuthService implements AuthApi {
    private final AuthRepository repository;
    private final AuthSettings settings;
    private final Clock clock;
    private final TotpService totp;
    private final FeatureSettings features;
    private final ExecutorService executor;
    private final AtomicLong generations = new AtomicLong();
    private final ConcurrentMap<UUID, Long> joining = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AttemptState> failedAttempts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AttemptState> failedTotpAttempts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, TotpSetup> pendingTotpSetups = new ConcurrentHashMap<>();
    private final IpBanStore bans = new IpBanStore();

    public AuthService(AuthRepository repository, AuthSettings settings) {
        this(repository, settings, new TotpService(repository, randomKey()), FeatureSettings.defaults(), Clock.systemUTC());
    }

    public AuthService(AuthRepository repository, AuthSettings settings, Clock clock) {
        this(repository, settings, new TotpService(repository, randomKey()), FeatureSettings.defaults(), clock);
    }

    public AuthService(AuthRepository repository, AuthSettings settings, TotpService totp) {
        this(repository, settings, totp, FeatureSettings.defaults(), Clock.systemUTC());
    }

    public AuthService(AuthRepository repository, AuthSettings settings, TotpService totp, FeatureSettings features) {
        this(repository, settings, totp, features, Clock.systemUTC());
    }

    AuthService(AuthRepository repository, AuthSettings settings, TotpService totp, FeatureSettings features, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.totp = totp;
        this.features = features;
        this.clock = clock;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "pnauth-worker");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(2, threadFactory);
    }

    @Override
    public CompletableFuture<AuthStatus> onJoin(UUID uniqueId, String username) {
        return onJoin(uniqueId, username, null);
    }

    @Override
    public CompletableFuture<AuthStatus> onJoin(UUID uniqueId, String username, String ip) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(AuthStatus.NOT_LOADED);
        }
        long generation = generations.incrementAndGet();
        joining.put(uniqueId, generation);
        failedAttempts.remove(uniqueId);
        String normalizedUsername = normalizeUsername(username);
        return async(() -> {
            Optional<AuthRecord> found = repository.findByUniqueId(uniqueId);
            AuthRecord record = found.orElse(null);
            if (record != null && !record.username().equals(normalizedUsername)) {
                repository.updateUsername(uniqueId, normalizedUsername);
                record = new AuthRecord(
                        record.uniqueId(),
                        normalizedUsername,
                        username,
                        record.passwordHash(),
                        record.registeredAt(),
                        record.lastLoginAt(),
                        record.premium(),
                        record.registeredIp(),
                        record.lastIp(),
                        record.totpSecret(),
                        record.dialogPreference()
                );
            }
            AuthRecord loaded = record;
            boolean sessionValid = loaded != null && ((loaded.premium() && features.premiumEnabled())
                    || (ip != null && ip.equals(loaded.lastIp()) && loaded.lastLoginAt() != null
                    && clock.millis() - loaded.lastLoginAt() <= features.sessionLifetime().toMillis()));
            AuthStatus status = loaded == null
                    ? AuthStatus.UNREGISTERED
                    : sessionValid ? AuthStatus.AUTHENTICATED : AuthStatus.UNAUTHENTICATED;
            if (joining.get(uniqueId) != null && joining.get(uniqueId) == generation) {
                sessions.put(uniqueId, new Session(generation, loaded, status, ip));
            }
            return status;
        });
    }

    @Override
    public void onQuit(UUID uniqueId) {
        if (uniqueId == null) {
            return;
        }
        joining.remove(uniqueId);
        sessions.remove(uniqueId);
        failedAttempts.remove(uniqueId);
    }

    @Override
    public CompletableFuture<AuthResult> register(
            UUID uniqueId,
            String username,
            String password,
            String confirmation
    ) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(AuthResult.NOT_JOINED);
        }
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null) {
                return AuthResult.NOT_JOINED;
            }
            if (session.record != null) {
                return AuthResult.ALREADY_REGISTERED;
            }
            if (!settings.isPasswordValid(password)) {
                return AuthResult.INVALID_PASSWORD_FORMAT;
            }
            if (!password.equals(confirmation)) {
                return AuthResult.PASSWORDS_DO_NOT_MATCH;
            }

            String normalizedUsername = normalizeUsername(username);
            if (!settings.isUsernameValid(normalizedUsername)) {
                return AuthResult.INVALID_USERNAME;
            }
            if (repository.findByUsername(normalizedUsername).isPresent()) {
                return AuthResult.USERNAME_TAKEN;
            }
            long now = clock.millis();
            PasswordHash passwordHash = PasswordHasher.hash(password, settings);
            AuthRecord record = new AuthRecord(
                    uniqueId, normalizedUsername, username, passwordHash, now, now, false, session.ip, session.ip, null
            );
            if (!repository.create(record)) {
                return repository.findByUniqueId(uniqueId).isPresent()
                        ? AuthResult.ALREADY_REGISTERED
                        : AuthResult.USERNAME_TAKEN;
            }
            replaceSession(uniqueId, session, new Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> login(UUID uniqueId, String password) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(AuthResult.NOT_JOINED);
        }
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null) {
                return AuthResult.NOT_JOINED;
            }
            if (session.record == null) {
                return AuthResult.NOT_REGISTERED;
            }
            if (session.status == AuthStatus.AUTHENTICATED) {
                return AuthResult.ALREADY_AUTHENTICATED;
            }
            if (isLocked(uniqueId)) {
                return AuthResult.LOCKED_OUT;
            }
            if (password == null || !PasswordHasher.matches(password, session.record.passwordHash())) {
                return failedLogin(uniqueId);
            }

            if (features.totpEnabled() && session.record.totpSecret() != null && !session.record.totpSecret().isBlank()) {
                replaceSession(uniqueId, session, new Session(session.generation, session.record, AuthStatus.TOTP_PENDING, session.ip));
                return AuthResult.TOTP_REQUIRED;
            }

            return authenticate(uniqueId, session);
        });
    }

    @Override
    public CompletableFuture<AuthResult> logout(UUID uniqueId) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(AuthResult.NOT_JOINED);
        }
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null) {
                return AuthResult.NOT_JOINED;
            }
            if (session.status != AuthStatus.AUTHENTICATED) {
                return AuthResult.NOT_AUTHENTICATED;
            }
            replaceSession(uniqueId, session, new Session(session.generation, session.record, AuthStatus.UNAUTHENTICATED));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> changePassword(UUID uniqueId, String oldPassword, String newPassword) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(AuthResult.NOT_JOINED);
        }
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null) {
                return AuthResult.NOT_JOINED;
            }
            if (session.status != AuthStatus.AUTHENTICATED || session.record == null) {
                return AuthResult.NOT_AUTHENTICATED;
            }
            if (oldPassword == null || !PasswordHasher.matches(oldPassword, session.record.passwordHash())) {
                return AuthResult.INVALID_PASSWORD;
            }
            if (!settings.isPasswordValid(newPassword)) {
                return AuthResult.INVALID_PASSWORD_FORMAT;
            }
            PasswordHash passwordHash = PasswordHasher.hash(newPassword, settings);
            repository.updatePassword(uniqueId, passwordHash);
            AuthRecord record = new AuthRecord(
                    session.record.uniqueId(),
                    session.record.username(),
                    session.record.realName(),
                    passwordHash,
                    session.record.registeredAt(),
                    session.record.lastLoginAt(),
                    session.record.premium(),
                    session.record.registeredIp(),
                    session.record.lastIp(),
                    session.record.totpSecret(),
                    session.record.dialogPreference()
            );
            replaceSession(uniqueId, session, new Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<TotpSetup> beginTotpSetup(UUID uniqueId, String password, String issuer) {
        if (uniqueId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Player is not joined"));
        }
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null || session.status != AuthStatus.AUTHENTICATED) {
                throw new IllegalStateException("Player is not authenticated");
            }
            if (!features.totpEnabled()) {
                throw new IllegalStateException("TOTP is disabled");
            }
            if (session.record.totpSecret() != null && !session.record.totpSecret().isBlank()) {
                throw new IllegalStateException("TOTP is already enabled");
            }
            if (password != null && !password.isBlank()
                    && !PasswordHasher.matches(password, session.record.passwordHash())) {
                throw new IllegalStateException("Invalid password");
            }
            String secret = totp.generateSecret();
            TotpSetup setup = new TotpSetup(
                    secret,
                    totp.provisioningUri(issuer, session.record.realName(), secret),
                    totp.generateRecoveryCodes(features.recoveryCodesAmount())
            );
            pendingTotpSetups.put(uniqueId, setup);
            return setup;
        });
    }

    @Override
    public CompletableFuture<AuthResult> confirmTotpSetup(UUID uniqueId, String code) {
        return async(() -> {
            TotpSetup setup = pendingTotpSetups.get(uniqueId);
            Session session = sessions.get(uniqueId);
            if (setup == null || session == null) return AuthResult.TOTP_SETUP_REQUIRED;
            if (!totp.verify(setup.secret(), code)) return AuthResult.TOTP_INVALID;
            repository.updateTotpSecret(uniqueId, totp.encrypt(setup.secret()));
            totp.saveRecoveryCodes(uniqueId, setup.recoveryCodes());
            String encryptedSecret = totp.encrypt(setup.secret());
            AuthRecord record = withTotp(session.record, encryptedSecret);
            replaceSession(uniqueId, session, new Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip));
            pendingTotpSetups.remove(uniqueId);
            return AuthResult.TOTP_ENABLED;
        });
    }

    @Override
    public CompletableFuture<AuthResult> verifyTotp(UUID uniqueId, String code) {
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return AuthResult.NOT_JOINED;
            if (session.status != AuthStatus.TOTP_PENDING) return AuthResult.TOTP_NOT_ENABLED;
            String secret = totp.decrypt(session.record.totpSecret());
            boolean valid = totp.verify(secret, code) || totp.consumeRecoveryCode(uniqueId, code);
            if (!valid) {
                AttemptState state = failedTotpAttempts.computeIfAbsent(uniqueId, ignored -> new AttemptState());
                synchronized (state) {
                    state.failures++;
                    if (state.failures >= features.totpMaxAttempts()) {
                        Session pending = sessions.get(uniqueId);
                        if (features.banOnFailedLogin() && pending != null) {
                            bans.ban(pending.ip, features.banDuration());
                        }
                        return AuthResult.LOCKED_OUT;
                    }
                }
                return AuthResult.TOTP_INVALID;
            }
            failedTotpAttempts.remove(uniqueId);
            return authenticate(uniqueId, session);
        });
    }

    @Override
    public CompletableFuture<AuthResult> disableTotp(UUID uniqueId, String password, String code) {
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return AuthResult.NOT_JOINED;
            if (session.record.totpSecret() == null || session.record.totpSecret().isBlank()) return AuthResult.TOTP_NOT_ENABLED;
            if (password != null && !password.isBlank() && !PasswordHasher.matches(password, session.record.passwordHash())) {
                return AuthResult.INVALID_PASSWORD;
            }
            String secret = totp.decrypt(session.record.totpSecret());
            if (!totp.verify(secret, code) && !totp.consumeRecoveryCode(uniqueId, code)) {
                return AuthResult.TOTP_INVALID;
            }
            repository.updateTotpSecret(uniqueId, null);
            repository.clearRecoveryCodes(uniqueId);
            replaceSession(uniqueId, session, new Session(session.generation, withTotp(session.record, null), session.status, session.ip));
            return AuthResult.TOTP_DISABLED;
        });
    }

    @Override
    public AuthStatus status(UUID uniqueId) {
        Session session = sessions.get(uniqueId);
        return session == null ? AuthStatus.NOT_LOADED : session.status;
    }

    @Override
    public Optional<AuthUser> user(UUID uniqueId) {
        Session session = sessions.get(uniqueId);
        return session == null || session.record == null
                ? Optional.empty()
                : Optional.of(session.record.toApiUser());
    }

    @Override
    public boolean isAuthenticated(UUID uniqueId) {
        return status(uniqueId) == AuthStatus.AUTHENTICATED;
    }

    @Override
    public CompletableFuture<Boolean> isPremium(String username) {
        return async(() -> repository.findByUsername(normalizeUsername(username))
                .map(AuthRecord::premium)
                .orElse(false));
    }

    @Override
    public CompletableFuture<AuthResult> togglePremium(UUID uniqueId) {
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return AuthResult.NOT_JOINED;
            boolean premium = !session.record.premium();
            repository.updatePremium(uniqueId, premium);
            AuthRecord record = new AuthRecord(
                    session.record.uniqueId(), session.record.username(), session.record.realName(), session.record.passwordHash(),
                    session.record.registeredAt(), session.record.lastLoginAt(), premium, session.record.registeredIp(),
                    session.record.lastIp(), session.record.totpSecret(), session.record.dialogPreference()
            );
            replaceSession(uniqueId, session, new Session(session.generation, record, session.status, session.ip));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> unregister(String username) {
        return async(() -> repository.findByUsername(normalizeUsername(username))
                .map(record -> {
                    repository.deleteByUniqueId(record.uniqueId());
                    sessions.remove(record.uniqueId());
                    joining.remove(record.uniqueId());
                    return AuthResult.SUCCESS;
                })
                .orElse(AuthResult.PLAYER_NOT_FOUND));
    }

    @Override
    public CompletableFuture<AuthResult> unregister(UUID uniqueId, String password) {
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return AuthResult.NOT_JOINED;
            if (password == null || !PasswordHasher.matches(password, session.record.passwordHash())) {
                return AuthResult.INVALID_PASSWORD;
            }
            repository.deleteByUniqueId(uniqueId);
            sessions.remove(uniqueId);
            joining.remove(uniqueId);
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> adminChangePassword(String username, String newPassword) {
        return async(() -> {
            AuthRecord record = repository.findByUsername(normalizeUsername(username)).orElse(null);
            if (record == null) return AuthResult.PLAYER_NOT_FOUND;
            if (!settings.isPasswordValid(newPassword)) return AuthResult.INVALID_PASSWORD_FORMAT;
            PasswordHash passwordHash = PasswordHasher.hash(newPassword, settings);
            repository.updatePassword(record.uniqueId(), passwordHash);
            Session session = sessions.get(record.uniqueId());
            if (session != null) {
                AuthRecord updated = new AuthRecord(
                        record.uniqueId(), record.username(), record.realName(), passwordHash, record.registeredAt(),
                        record.lastLoginAt(), record.premium(), record.registeredIp(), record.lastIp(), record.totpSecret(),
                        record.dialogPreference()
                );
                replaceSession(record.uniqueId(), session, new Session(session.generation, updated, session.status, session.ip));
            }
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> forceRegister(String username, String password) {
        return async(() -> {
            String normalizedUsername = normalizeUsername(username);
            if (!settings.isUsernameValid(normalizedUsername)) return AuthResult.INVALID_USERNAME;
            if (!settings.isPasswordValid(password)) return AuthResult.INVALID_PASSWORD_FORMAT;
            if (repository.findByUsername(normalizedUsername).isPresent()) return AuthResult.ALREADY_REGISTERED;

            UUID uniqueId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (repository.findByUniqueId(uniqueId).isPresent()) return AuthResult.ALREADY_REGISTERED;
            AuthRecord record = new AuthRecord(
                    uniqueId, normalizedUsername, username, PasswordHasher.hash(password, settings), clock.millis(),
                    null, false, null, null, null
            );
            return repository.create(record) ? AuthResult.SUCCESS : AuthResult.ALREADY_REGISTERED;
        });
    }

    @Override
    public CompletableFuture<AuthResult> forceLogin(String username) {
        return async(() -> sessions.values().stream()
                .filter(session -> session.record != null && session.record.username().equalsIgnoreCase(username))
                .findFirst()
                .map(session -> session.status == AuthStatus.AUTHENTICATED
                        ? AuthResult.ALREADY_AUTHENTICATED
                        : authenticate(session.record.uniqueId(), session))
                .orElse(AuthResult.PLAYER_NOT_FOUND));
    }

    @Override
    public CompletableFuture<AuthResult> togglePremium(String username) {
        return async(() -> repository.findByUsername(normalizeUsername(username))
                .map(record -> {
                    boolean premium = !record.premium();
                    repository.updatePremium(record.uniqueId(), premium);
                    Session session = sessions.get(record.uniqueId());
                    if (session != null) {
                        AuthRecord updated = new AuthRecord(
                                record.uniqueId(), record.username(), record.realName(), record.passwordHash(),
                                record.registeredAt(), record.lastLoginAt(), premium, record.registeredIp(),
                                record.lastIp(), record.totpSecret(), record.dialogPreference()
                        );
                        replaceSession(record.uniqueId(), session, new Session(session.generation, updated, session.status, session.ip));
                    }
                    return AuthResult.SUCCESS;
                })
                .orElse(AuthResult.PLAYER_NOT_FOUND));
    }

    @Override
    public CompletableFuture<AdmissionDecision> checkAdmission(String username, String ip, int onlineAccountsFromIp) {
        return async(() -> {
            if (bans.isBanned(ip)) {
                return new AdmissionDecision(false, false, AdmissionDecision.Reason.BANNED);
            }
            if (!features.excludedIps().contains(ip) && onlineAccountsFromIp >= features.maxOnlineAccountsPerIp()) {
                return new AdmissionDecision(false, false, AdmissionDecision.Reason.ONLINE_IP_LIMIT);
            }
            AuthRecord record = repository.findByUsername(normalizeUsername(username)).orElse(null);
            if (record == null && !features.excludedIps().contains(ip)
                    && repository.countRegisteredIp(ip) >= features.maxRegisteredAccountsPerIp()) {
                return new AdmissionDecision(false, false, AdmissionDecision.Reason.REGISTERED_IP_LIMIT);
            }
            return new AdmissionDecision(true, record != null && record.premium() && features.premiumEnabled(),
                    AdmissionDecision.Reason.ALLOWED);
        });
    }

    @Override
    public DialogPreference dialogPreference(UUID uniqueId) {
        Session session = sessions.get(uniqueId);
        return session == null || session.record == null ? DialogPreference.AUTO : session.record.dialogPreference();
    }

    @Override
    public CompletableFuture<AuthResult> setDialogPreference(UUID uniqueId, DialogPreference preference) {
        DialogPreference selected = preference == null ? DialogPreference.AUTO : preference;
        return async(() -> {
            if (!features.dialogs().allowPlayerPreference()) return AuthResult.DIALOG_PREFERENCE_DISABLED;
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return AuthResult.NOT_JOINED;
            repository.updateDialogPreference(uniqueId, selected);
            AuthRecord record = new AuthRecord(
                    session.record.uniqueId(), session.record.username(), session.record.realName(), session.record.passwordHash(),
                    session.record.registeredAt(), session.record.lastLoginAt(), session.record.premium(), session.record.registeredIp(),
                    session.record.lastIp(), session.record.totpSecret(), selected
            );
            replaceSession(uniqueId, session, new Session(session.generation, record, session.status, session.ip));
            return AuthResult.DIALOG_PREFERENCE_UPDATED;
        });
    }

    @Override
    public boolean shouldUseDialog(UUID uniqueId, int clientProtocol, boolean platformSupportsDialogs) {
        if (!platformSupportsDialogs || !features.dialogs().enabled()
                || clientProtocol < features.dialogs().minClientProtocol()) return false;
        return dialogPreference(uniqueId) != DialogPreference.DISABLED;
    }

    @Override
    public boolean shouldUseCommandFallback(UUID uniqueId, int clientProtocol, boolean platformSupportsDialogs) {
        if (shouldUseDialog(uniqueId, clientProtocol, platformSupportsDialogs)) return false;
        return features.dialogs().fallbackToCommands();
    }

    @Override
    public void close() {
        executor.shutdownNow();
        repository.close();
        sessions.clear();
        joining.clear();
        failedAttempts.clear();
        failedTotpAttempts.clear();
        pendingTotpSetups.clear();
        bans.clear();
    }

    private AuthResult failedLogin(UUID uniqueId) {
        AttemptState state = failedAttempts.computeIfAbsent(uniqueId, ignored -> new AttemptState());
        synchronized (state) {
            state.failures++;
            if (state.failures >= settings.maxLoginAttempts()) {
                state.lockedUntil = clock.millis() + settings.lockoutDuration().toMillis();
                Session session = sessions.get(uniqueId);
                if (features.banOnFailedLogin() && session != null) {
                    bans.ban(session.ip, features.banDuration());
                }
                return AuthResult.LOCKED_OUT;
            }
            return AuthResult.INVALID_PASSWORD;
        }
    }

    private boolean isLocked(UUID uniqueId) {
        AttemptState state = failedAttempts.get(uniqueId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.lockedUntil != 0 && state.lockedUntil <= clock.millis()) {
                failedAttempts.remove(uniqueId, state);
                return false;
            }
            return state.lockedUntil != 0;
        }
    }

    private void replaceSession(UUID uniqueId, Session expected, Session replacement) {
        sessions.replace(uniqueId, expected, replacement);
    }

    private <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private AuthResult authenticate(UUID uniqueId, Session session) {
        failedAttempts.remove(uniqueId);
        long now = clock.millis();
        repository.updateLastLogin(uniqueId, now);
        if (session.ip != null) repository.updateLastIp(uniqueId, session.ip);
        AuthRecord record = new AuthRecord(
                session.record.uniqueId(), session.record.username(), session.record.realName(),
                session.record.passwordHash(), session.record.registeredAt(), now,
                session.record.premium(), session.record.registeredIp(), session.record.lastIp(), session.record.totpSecret(),
                session.record.dialogPreference()
        );
        replaceSession(uniqueId, session, new Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip));
        return AuthResult.SUCCESS;
    }

    private static AuthRecord withTotp(AuthRecord record, String secret) {
        return new AuthRecord(
                record.uniqueId(), record.username(), record.realName(), record.passwordHash(), record.registeredAt(),
                record.lastLoginAt(), record.premium(), record.registeredIp(), record.lastIp(), secret,
                record.dialogPreference()
        );
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private record Session(long generation, AuthRecord record, AuthStatus status, String ip) {
        private Session(long generation, AuthRecord record, AuthStatus status) {
            this(generation, record, status, null);
        }
    }

    private static final class AttemptState {
        private int failures;
        private long lockedUntil;
    }
}
