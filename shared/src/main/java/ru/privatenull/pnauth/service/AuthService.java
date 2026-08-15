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
import ru.privatenull.pnauth.config.ProcessingTitleSettings;
import ru.privatenull.pnauth.message.AnimatedGradient;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.security.IpBanStore;
import ru.privatenull.pnauth.security.PasswordHasher;
import ru.privatenull.pnauth.security.TotpService;
import ru.privatenull.pnauth.event.AuthEventBus;
import ru.privatenull.pnauth.event.SimpleAuthEventBus;
import ru.privatenull.pnauth.event.TotpStateChangedEvent;
import ru.privatenull.pnauth.event.UserAuthenticatedEvent;
import ru.privatenull.pnauth.event.UserJoinedEvent;
import ru.privatenull.pnauth.event.UserLoggedOutEvent;
import ru.privatenull.pnauth.event.UserUnregisteredEvent;
import ru.privatenull.pnauth.event.PreAuthOperationEvent;
import ru.privatenull.pnauth.event.AuthOperationCompletedEvent;
import ru.privatenull.pnauth.event.VerificationRequiredEvent;
import ru.privatenull.pnauth.event.*;
import ru.privatenull.pnauth.extension.AuthExtensionRegistry;
import ru.privatenull.pnauth.extension.DefaultAuthExtensionRegistry;
import ru.privatenull.pnauth.extension.AuthOperation;
import ru.privatenull.pnauth.extension.AuthOperationContext;
import ru.privatenull.pnauth.extension.AuthPolicyDecision;
import ru.privatenull.pnauth.extension.AuthOperationRejectedException;
import ru.privatenull.pnauth.display.*;
import ru.privatenull.pnauth.kernel.service.DefaultServiceRegistry;
import ru.privatenull.pnauth.kernel.service.ServiceRegistry;
import ru.privatenull.pnauth.platform.PnPlatform;
import ru.privatenull.pnauth.platform.UnavailablePlatform;
import ru.privatenull.pnauth.platform.TaskHandle;

import java.time.Clock;
import java.time.Duration;
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
    private final ConcurrentMap<UUID, PendingTotpSetup> pendingTotpSetups = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Runnable> verificationContinuations = new ConcurrentHashMap<>();
    private final IpBanStore bans = new IpBanStore();
    private final AuthEventBus events;
    private final AuthExtensionRegistry extensions;
    private volatile PlayerDisplay display = new NoopPlayerDisplay();
    private volatile ProcessingPresentation processingPresentation = ProcessingPresentation.disabled();
    private final ConcurrentMap<UUID, ProcessingState> processingOperations = new ConcurrentHashMap<>();
    private volatile PnPlatform platform = new UnavailablePlatform();
    private final ConcurrentMap<String, java.util.List<DisplayHandle>> verificationDisplays = new ConcurrentHashMap<>();
    private final ServiceRegistry services = new DefaultServiceRegistry();

    public AuthService(AuthRepository repository, AuthSettings settings) {
        this(repository, settings, new TotpService(repository, randomKey()), FeatureSettings.defaults(), Clock.systemUTC(), new SimpleAuthEventBus());
    }

    public AuthService(AuthRepository repository, AuthSettings settings, Clock clock) {
        this(repository, settings, new TotpService(repository, randomKey()), FeatureSettings.defaults(), clock, new SimpleAuthEventBus());
    }

    public AuthService(AuthRepository repository, AuthSettings settings, TotpService totp) {
        this(repository, settings, totp, FeatureSettings.defaults(), Clock.systemUTC(), new SimpleAuthEventBus());
    }

    public AuthService(AuthRepository repository, AuthSettings settings, TotpService totp, FeatureSettings features) {
        this(repository, settings, totp, features, Clock.systemUTC(), new SimpleAuthEventBus());
    }

    AuthService(AuthRepository repository, AuthSettings settings, TotpService totp, FeatureSettings features, Clock clock) {
        this(repository, settings, totp, features, clock, new SimpleAuthEventBus());
    }

    public AuthService(AuthRepository repository, AuthSettings settings, TotpService totp,
                       FeatureSettings features, AuthEventBus events) {
        this(repository, settings, totp, features, Clock.systemUTC(), events, new DefaultAuthExtensionRegistry());
    }

    AuthService(AuthRepository repository, AuthSettings settings, TotpService totp, FeatureSettings features,
                Clock clock, AuthEventBus events) {
        this(repository, settings, totp, features, clock, events, new DefaultAuthExtensionRegistry());
    }

    public AuthService(AuthRepository repository, AuthSettings settings, TotpService totp, FeatureSettings features,
                       AuthEventBus events, AuthExtensionRegistry extensions) {
        this(repository, settings, totp, features, Clock.systemUTC(), events, extensions);
    }

    AuthService(AuthRepository repository, AuthSettings settings, TotpService totp, FeatureSettings features,
                Clock clock, AuthEventBus events, AuthExtensionRegistry extensions) {
        this.repository = repository;
        this.settings = settings;
        this.totp = totp;
        this.features = features;
        this.clock = clock;
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.extensions = java.util.Objects.requireNonNull(extensions, "extensions");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "pnauth-worker");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(2, threadFactory);
        this.extensions.onTicket(ticket -> {
            if (ticket.status() == ru.privatenull.pnauth.extension.VerificationTicket.Status.PENDING) {
                events.publish(new VerificationRequiredEvent(ticket));
                if (ticket.uniqueId() != null) {
                    java.time.Duration remaining = java.time.Duration.between(java.time.Instant.now(), ticket.expiresAt());
                    verificationDisplays.put(ticket.id(), java.util.List.of(
                            display.actionBar(ticket.uniqueId(), "pnauth:verification:action:" + ticket.id(), new ActionBarOptions(
                                    ticket.message(), java.time.Duration.ofSeconds(1), remaining)),
                            display.bossBar(ticket.uniqueId(), "pnauth:verification:boss:" + ticket.id(), new BossBarOptions(
                                    ticket.message(), 1F, BossBarColor.PURPLE, BossBarOverlay.PROGRESS,
                                    false, false, false, remaining))));
                }
            } else {
                events.publish(new VerificationResolvedEvent(ticket));
                java.util.List<DisplayHandle> handles = verificationDisplays.remove(ticket.id());
                if (handles != null) handles.forEach(DisplayHandle::close);
                Runnable continuation = verificationContinuations.remove(ticket.id());
                if (ticket.status() == ru.privatenull.pnauth.extension.VerificationTicket.Status.APPROVED
                        && continuation != null) executor.execute(continuation);
            }
        });
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
        String normalizedUsername = normalizeUsername(username);
        return async(() -> {
            Optional<AuthRecord> found = repository.findByUniqueId(uniqueId);
            if (found.isEmpty() && !normalizedUsername.isEmpty()) {
                // Velocity can assign a different UUID when an account changes between
                // online and offline mode. The account itself remains username-based.
                found = repository.findByUsername(normalizedUsername);
            }
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
            boolean hasTotp = loaded != null && features.totpEnabled()
                    && loaded.totpSecret() != null && !loaded.totpSecret().isBlank();
            boolean trustedPremiumLogin = loaded != null && loaded.premium() && features.premiumEnabled();
            long lastLoginAge = loaded == null || loaded.lastLoginAt() == null
                    ? Long.MAX_VALUE : clock.millis() - loaded.lastLoginAt();
            boolean trustedIpSession = loaded != null && features.restoreSessionOnSameIp()
                    && ip != null && ip.equals(loaded.lastIp()) && loaded.lastLoginAt() != null
                    && lastLoginAge >= 0 && lastLoginAge <= features.sessionLifetime().toMillis();
            boolean sessionValid = trustedPremiumLogin || trustedIpSession;
            AuthStatus status = loaded == null
                    ? AuthStatus.UNREGISTERED
                    : sessionValid ? (hasTotp ? AuthStatus.TOTP_PENDING : AuthStatus.AUTHENTICATED)
                    : AuthStatus.UNAUTHENTICATED;
            if (sessionValid && loaded != null && !loaded.uniqueId().equals(uniqueId)) {
                loaded = reassignIdentity(loaded, uniqueId);
            }
            if (joining.get(uniqueId) != null && joining.get(uniqueId) == generation) {
                sessions.put(uniqueId, new Session(generation, loaded, status, ip));
                events.publish(new UserJoinedEvent(uniqueId, username, ip, status));
                if (status == AuthStatus.AUTHENTICATED) {
                    events.publish(new UserAuthenticatedEvent(uniqueId, username,
                            trustedPremiumLogin ? UserAuthenticatedEvent.Cause.PREMIUM
                                    : UserAuthenticatedEvent.Cause.SESSION));
                }
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
        Session departed = sessions.remove(uniqueId);
        pendingTotpSetups.remove(uniqueId);
        ProcessingState processing = processingOperations.remove(uniqueId);
        if (processing != null) processing.close();
        display.clear(uniqueId);
        platform.tasks().cancelAll(uniqueId);
        platform.dialogs().closeAll(uniqueId);
        events.publish(new UserQuitEvent(uniqueId,
                departed == null || departed.record == null ? "" : departed.record.realName()));
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
        Session registrationSession = sessions.get(uniqueId);
        CompletableFuture<AuthResult> operation = guarded(new AuthOperationContext(AuthOperation.REGISTER, uniqueId, username,
                registrationSession == null ? null : registrationSession.ip, java.util.Map.of()), () -> {
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
            events.publish(new UserRegisteredEvent(uniqueId, record.realName(), session.ip, false));
            events.publish(new UserAuthenticatedEvent(uniqueId, record.realName(), UserAuthenticatedEvent.Cause.REGISTER));
            return AuthResult.SUCCESS;
        });
        return withProcessingTitle(uniqueId, operation);
    }

    @Override
    public CompletableFuture<AuthResult> login(UUID uniqueId, String password) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(AuthResult.NOT_JOINED);
        }
        Session initial = sessions.get(uniqueId);
        AuthOperationContext request = new AuthOperationContext(AuthOperation.LOGIN,
                ru.privatenull.pnauth.extension.AuthPhase.REQUEST, uniqueId,
                initial == null || initial.record == null ? "" : initial.record.realName(),
                initial == null ? null : initial.ip, java.util.Map.of());
        PreAuthOperationEvent preEvent = new PreAuthOperationEvent(request);
        events.publish(preEvent);
        if (preEvent.cancelled()) return completedOperation(request, AuthResult.OPERATION_DENIED);
        CompletableFuture<AuthResult> operation = async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null) {
                return new LoginPreparation(null, AuthResult.NOT_JOINED);
            }
            if (session.record == null) {
                return new LoginPreparation(null, AuthResult.NOT_REGISTERED);
            }
            if (session.status == AuthStatus.AUTHENTICATED) {
                return new LoginPreparation(null, AuthResult.ALREADY_AUTHENTICATED);
            }
            UUID attemptKey = attemptKey(uniqueId, session);
            if (isLocked(attemptKey)) {
                return new LoginPreparation(null, AuthResult.LOCKED_OUT);
            }
            if (password == null || password.length() > settings.maxPasswordLength()
                    || !PasswordHasher.matches(password, session.record.passwordHash())) {
                return new LoginPreparation(null, failedLogin(attemptKey, session));
            }

            session = upgradePasswordHash(uniqueId, session, password);

            if (features.totpEnabled() && session.record.totpSecret() != null && !session.record.totpSecret().isBlank()) {
                replaceSession(uniqueId, session, new Session(session.generation, session.record, AuthStatus.TOTP_PENDING, session.ip));
                return new LoginPreparation(null, AuthResult.TOTP_REQUIRED);
            }
            return new LoginPreparation(session, null);
        }).thenCompose(prepared -> {
            if (prepared.result != null) return completedOperation(request, prepared.result);
            AuthOperationContext verified = request.at(ru.privatenull.pnauth.extension.AuthPhase.CREDENTIAL_VERIFIED);
            return extensions.evaluate(verified).thenCompose(decision -> {
                if (decision.type() == AuthPolicyDecision.Type.DENY) {
                    return completedOperation(verified, AuthResult.OPERATION_DENIED);
                }
                if (decision.type() == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) {
                    attachContinuation(uniqueId, () -> completeVerifiedAuthentication(
                            uniqueId, prepared.session, verified, UserAuthenticatedEvent.Cause.PASSWORD));
                    return completedOperation(verified, AuthResult.ADDITIONAL_VERIFICATION_REQUIRED);
                }
                return async(() -> authenticate(uniqueId, prepared.session, UserAuthenticatedEvent.Cause.PASSWORD))
                        .thenApply(result -> finishOperation(verified, result));
            });
        }).toCompletableFuture();
        return withProcessingTitle(uniqueId, operation);
    }

    @Override
    public CompletableFuture<AuthResult> logout(UUID uniqueId) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(AuthResult.NOT_JOINED);
        }
        return guarded(AuthOperation.LOGOUT, uniqueId, () -> {
            Session session = sessions.get(uniqueId);
            if (session == null) {
                return AuthResult.NOT_JOINED;
            }
            if (session.status != AuthStatus.AUTHENTICATED) {
                return AuthResult.NOT_AUTHENTICATED;
            }
            pendingTotpSetups.remove(uniqueId);
            repository.updateLastIp(session.record.uniqueId(), null);
            AuthRecord record = withLastIp(session.record, null);
            replaceSession(uniqueId, session, new Session(
                    session.generation, record, AuthStatus.UNAUTHENTICATED, session.ip));
            events.publish(new UserLoggedOutEvent(uniqueId, record.realName()));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> changePassword(UUID uniqueId, String oldPassword, String newPassword) {
        if (uniqueId == null) {
            return CompletableFuture.completedFuture(AuthResult.NOT_JOINED);
        }
        return guarded(AuthOperation.CHANGE_PASSWORD, uniqueId, () -> {
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
            events.publish(new PasswordChangedEvent(uniqueId, record.realName(), false));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<TotpSetup> beginTotpSetup(UUID uniqueId, String password, String issuer) {
        if (uniqueId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Player is not joined"));
        }
        Session current = sessions.get(uniqueId);
        return guardedValue(AuthOperationContext.user(AuthOperation.TOTP_SETUP, uniqueId,
                current == null || current.record == null ? "" : current.record.realName(),
                current == null ? null : current.ip), () -> {
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
            if (password == null || password.isBlank() || password.length() > settings.maxPasswordLength()
                    || !PasswordHasher.matches(password, session.record.passwordHash())) {
                throw new IllegalStateException("Invalid password");
            }
            String secret = totp.generateSecret();
            TotpSetup setup = new TotpSetup(
                    secret,
                    totp.provisioningUri(issuer, session.record.realName(), secret),
                    totp.generateRecoveryCodes(features.recoveryCodesAmount())
            );
            pendingTotpSetups.put(uniqueId, new PendingTotpSetup(
                    session.generation, setup, clock.millis() + features.totpSetupLifetime().toMillis()));
            events.publish(new TotpSetupStartedEvent(uniqueId, session.record.realName()));
            return setup;
        });
    }

    @Override
    public CompletableFuture<AuthResult> confirmTotpSetup(UUID uniqueId, String code) {
        return guarded(AuthOperation.TOTP_VERIFY, uniqueId, () -> confirmPendingTotpSetup(uniqueId, code));
    }

    @Override
    public CompletableFuture<AuthResult> verifyTotp(UUID uniqueId, String code) {
        // `/totp verify` also confirms a freshly created setup.
        if (pendingTotpSetups.containsKey(uniqueId)) return confirmTotpSetup(uniqueId, code);
        Session initial = sessions.get(uniqueId);
        AuthOperationContext request = new AuthOperationContext(AuthOperation.TOTP_VERIFY,
                ru.privatenull.pnauth.extension.AuthPhase.REQUEST, uniqueId,
                initial == null || initial.record == null ? "" : initial.record.realName(),
                initial == null ? null : initial.ip, java.util.Map.of());
        PreAuthOperationEvent preEvent = new PreAuthOperationEvent(request);
        events.publish(preEvent);
        if (preEvent.cancelled()) return completedOperation(request, AuthResult.OPERATION_DENIED);
        return async(() -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return new TotpPreparation(null, AuthResult.NOT_JOINED);
            if (session.status != AuthStatus.TOTP_PENDING) return new TotpPreparation(null, AuthResult.TOTP_NOT_ENABLED);
            UUID attemptKey = attemptKey(uniqueId, session);
            if (isTotpLocked(attemptKey)) return new TotpPreparation(null, AuthResult.LOCKED_OUT);
            boolean valid = verifyTotpOrRecoveryCode(session.record.uniqueId(), session.record.totpSecret(), code);
            if (!valid) {
                return new TotpPreparation(null, failedTotp(attemptKey, session));
            }
            failedTotpAttempts.remove(attemptKey);
            return new TotpPreparation(session, null);
        }).thenCompose(prepared -> {
            if (prepared.result != null) return completedOperation(request, prepared.result);
            AuthOperationContext verified = request.at(ru.privatenull.pnauth.extension.AuthPhase.CREDENTIAL_VERIFIED);
            return extensions.evaluate(verified).thenCompose(decision -> {
                if (decision.type() == AuthPolicyDecision.Type.DENY) return completedOperation(verified, AuthResult.OPERATION_DENIED);
                if (decision.type() == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) {
                    attachContinuation(uniqueId, () -> completeVerifiedAuthentication(
                            uniqueId, prepared.session, verified, UserAuthenticatedEvent.Cause.TOTP));
                    return completedOperation(verified, AuthResult.ADDITIONAL_VERIFICATION_REQUIRED);
                }
                return async(() -> authenticate(uniqueId, prepared.session, UserAuthenticatedEvent.Cause.TOTP))
                        .thenApply(result -> finishOperation(verified, result));
            });
        }).toCompletableFuture();
    }

    @Override
    public CompletableFuture<AuthResult> disableTotp(UUID uniqueId, String password, String code) {
        return guarded(AuthOperation.TOTP_DISABLE, uniqueId, () -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return AuthResult.NOT_JOINED;
            if (session.status != AuthStatus.AUTHENTICATED) return AuthResult.NOT_AUTHENTICATED;
            if (session.record.totpSecret() == null || session.record.totpSecret().isBlank()) return AuthResult.TOTP_NOT_ENABLED;
            UUID attemptKey = attemptKey(uniqueId, session);
            if (isTotpLocked(attemptKey)) return AuthResult.LOCKED_OUT;
            if (password == null || password.isBlank() || password.length() > settings.maxPasswordLength()
                    || !PasswordHasher.matches(password, session.record.passwordHash())) {
                return AuthResult.INVALID_PASSWORD;
            }
            if (!verifyTotpOrRecoveryCode(session.record.uniqueId(), session.record.totpSecret(), code)) {
                return failedTotp(attemptKey, session);
            }
            totp.clearTotpData(session.record.uniqueId());
            failedTotpAttempts.remove(attemptKey);
            replaceSession(uniqueId, session, new Session(session.generation, withTotp(session.record, null), session.status, session.ip));
            events.publish(new TotpStateChangedEvent(uniqueId, session.record.realName(), false));
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
        return guarded(AuthOperation.PREMIUM_CHANGE, uniqueId, () -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return AuthResult.NOT_JOINED;
            if (session.status != AuthStatus.AUTHENTICATED) return AuthResult.NOT_AUTHENTICATED;
            boolean premium = !session.record.premium();
            repository.updatePremium(uniqueId, premium);
            AuthRecord record = new AuthRecord(
                    session.record.uniqueId(), session.record.username(), session.record.realName(), session.record.passwordHash(),
                    session.record.registeredAt(), session.record.lastLoginAt(), premium, session.record.registeredIp(),
                    session.record.lastIp(), session.record.totpSecret(), session.record.dialogPreference()
            );
            replaceSession(uniqueId, session, new Session(session.generation, record, session.status, session.ip));
            events.publish(new PremiumStateChangedEvent(uniqueId, record.realName(), premium));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> unregister(String username) {
        String normalizedUsername = normalizeUsername(username);
        return guarded(new AuthOperationContext(AuthOperation.ADMIN_UNREGISTER, null, username, null, java.util.Map.of()),
                () -> repository.findByUsername(normalizedUsername)
                .map(record -> {
                    repository.deleteByUniqueId(record.uniqueId());
                    sessions.entrySet().removeIf(entry -> entry.getValue().record != null
                            && entry.getValue().record.username().equals(normalizedUsername));
                    joining.remove(record.uniqueId());
                    failedAttempts.remove(record.uniqueId());
                    failedTotpAttempts.remove(record.uniqueId());
                    events.publish(new UserUnregisteredEvent(record.uniqueId(), record.realName(), true));
                    return AuthResult.SUCCESS;
                })
                .orElse(AuthResult.PLAYER_NOT_FOUND));
    }

    @Override
    public CompletableFuture<AuthResult> unregister(UUID uniqueId, String password) {
        return guarded(AuthOperation.UNREGISTER, uniqueId, () -> {
            Session session = sessions.get(uniqueId);
            if (session == null || session.record == null) return AuthResult.NOT_JOINED;
            if (session.status != AuthStatus.AUTHENTICATED) return AuthResult.NOT_AUTHENTICATED;
            UUID attemptKey = attemptKey(uniqueId, session);
            if (isLocked(attemptKey)) return AuthResult.LOCKED_OUT;
            if (password == null || password.length() > settings.maxPasswordLength()
                    || !PasswordHasher.matches(password, session.record.passwordHash())) {
                return failedLogin(attemptKey, session);
            }
            repository.deleteByUniqueId(uniqueId);
            sessions.remove(uniqueId);
            joining.remove(uniqueId);
            pendingTotpSetups.remove(uniqueId);
            failedAttempts.remove(uniqueId);
            failedTotpAttempts.remove(uniqueId);
            events.publish(new UserUnregisteredEvent(uniqueId, session.record.realName(), false));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> adminChangePassword(String username, String newPassword) {
        return guarded(new AuthOperationContext(AuthOperation.ADMIN_CHANGE_PASSWORD, null, username, null, java.util.Map.of()), () -> {
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
            events.publish(new PasswordChangedEvent(record.uniqueId(), record.realName(), true));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> forceRegister(String username, String password) {
        return guarded(new AuthOperationContext(AuthOperation.ADMIN_FORCE_REGISTER, null, username, null, java.util.Map.of()), () -> {
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
            if (!repository.create(record)) return AuthResult.ALREADY_REGISTERED;
            events.publish(new UserRegisteredEvent(uniqueId, record.realName(), null, true));
            return AuthResult.SUCCESS;
        });
    }

    @Override
    public CompletableFuture<AuthResult> forceLogin(String username) {
        return guarded(new AuthOperationContext(AuthOperation.ADMIN_FORCE_LOGIN, null, username, null, java.util.Map.of()),
                () -> sessions.entrySet().stream()
                .filter(entry -> entry.getValue().record != null
                        && entry.getValue().record.username().equalsIgnoreCase(username))
                .findFirst()
                .map(entry -> entry.getValue().status == AuthStatus.AUTHENTICATED
                        ? AuthResult.ALREADY_AUTHENTICATED
                        : authenticate(entry.getKey(), entry.getValue(), UserAuthenticatedEvent.Cause.ADMIN))
                .orElse(AuthResult.PLAYER_NOT_FOUND));
    }

    @Override
    public CompletableFuture<AuthResult> togglePremium(String username) {
        return guarded(new AuthOperationContext(AuthOperation.PREMIUM_CHANGE, null, username, null, java.util.Map.of()),
                () -> repository.findByUsername(normalizeUsername(username))
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
                    events.publish(new PremiumStateChangedEvent(record.uniqueId(), record.realName(), premium));
                    return AuthResult.SUCCESS;
                })
                .orElse(AuthResult.PLAYER_NOT_FOUND));
    }

    @Override
    public CompletableFuture<AdmissionDecision> checkAdmission(String username, String ip, int onlineAccountsFromIp) {
        AuthOperationContext context = new AuthOperationContext(AuthOperation.ADMISSION, null, username, ip,
                java.util.Map.of("onlineAccountsFromIp", Integer.toString(onlineAccountsFromIp)));
        PreAuthOperationEvent preEvent = new PreAuthOperationEvent(context);
        events.publish(preEvent);
        if (preEvent.cancelled()) return completedAdmission(username, ip,
                new AdmissionDecision(false, false, AdmissionDecision.Reason.POLICY_DENIED));
        return extensions.evaluate(context).thenCompose(policy -> {
            if (policy.type() != AuthPolicyDecision.Type.ALLOW) return completedAdmission(username, ip,
                    new AdmissionDecision(false, false, AdmissionDecision.Reason.POLICY_DENIED));
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
            }).thenApply(decision -> {
                events.publish(new AdmissionEvaluatedEvent(username, ip, decision));
                return decision;
            });
        }).toCompletableFuture();
    }

    private CompletableFuture<AdmissionDecision> completedAdmission(
            String username, String ip, AdmissionDecision decision
    ) {
        events.publish(new AdmissionEvaluatedEvent(username, ip, decision));
        return CompletableFuture.completedFuture(decision);
    }

    @Override
    public DialogPreference dialogPreference(UUID uniqueId) {
        Session session = sessions.get(uniqueId);
        return session == null || session.record == null ? DialogPreference.AUTO : session.record.dialogPreference();
    }

    @Override
    public AuthEventBus events() {
        return events;
    }

    @Override
    public AuthExtensionRegistry extensions() {
        return extensions;
    }

    @Override
    public PlayerDisplay display() {
        return display;
    }

    public void installDisplay(PlayerDisplay display) {
        this.display = java.util.Objects.requireNonNull(display, "display");
    }

    /** Configures the localized title shown for the exact lifetime of a password operation. */
    public void installProcessingTitle(ProcessingTitleSettings settings, MessageFormat format, String title, String subtitle,
                                       String successTitle, String successSubtitle, String failureTitle, String failureSubtitle) {
        this.processingPresentation = new ProcessingPresentation(java.util.Objects.requireNonNull(settings, "settings"),
                format == null ? MessageFormat.LEGACY : format, title == null ? "" : title, subtitle == null ? "" : subtitle,
                successTitle == null ? "" : successTitle, successSubtitle == null ? "" : successSubtitle,
                failureTitle == null ? "" : failureTitle, failureSubtitle == null ? "" : failureSubtitle);
    }

    private CompletableFuture<AuthResult> withProcessingTitle(
            UUID uniqueId, CompletableFuture<AuthResult> operation) {
        ProcessingPresentation presentation = processingPresentation;
        ProcessingState state = processingOperations.compute(uniqueId, (playerId, current) -> {
            if (current != null) {
                current.references++;
                return current;
            }
            return startProcessingTitle(playerId, presentation);
        });
        CompletableFuture<AuthResult> visibleResult = new CompletableFuture<>();
        operation.whenComplete((result, failure) -> {
            processingOperations.computeIfPresent(uniqueId, (playerId, current) -> {
                if (current != state || --current.references > 0) {
                    visibleResult.complete(result);
                    return current;
                }
                long remaining = presentation.settings.timings().minimumDisplay().toMillis()
                        - (System.nanoTime() - current.startedAtNanos) / 1_000_000L;
                Runnable showResult = () -> showProcessingResult(uniqueId, current, presentation, result, failure, visibleResult);
                if (remaining > 0) current.finishTask = platform.scheduler().delayed(Duration.ofMillis(remaining), showResult);
                else showResult.run();
                return current;
            });
            if (!visibleResult.isDone() && !processingOperations.containsKey(uniqueId)) visibleResult.complete(result);
        });
        return visibleResult;
    }

    private void showProcessingResult(UUID uniqueId, ProcessingState state, ProcessingPresentation presentation,
                                      AuthResult result, Throwable failure, CompletableFuture<AuthResult> visibleResult) {
        if (state.title == null) {
            completeProcessingResult(uniqueId, state, result, failure, visibleResult);
            return;
        }
        state.stopAnimation();
        boolean success = failure == null && result == AuthResult.SUCCESS;
        Duration resultFadeIn = presentation.settings.timings().resultFadeIn();
        Duration resultDuration = presentation.settings.timings().resultDisplay();
        Duration resultFadeOut = presentation.settings.timings().resultFadeOut();
        state.title.timings(resultFadeIn, resultDuration, resultFadeOut);
        state.title.title(success ? presentation.successTitle : presentation.failureTitle);
        state.title.subtitle(success ? presentation.successSubtitle : presentation.failureSubtitle);
        Duration delay = resultFadeIn.plus(resultDuration).plus(resultFadeOut);
        if (delay.isZero()) completeProcessingResult(uniqueId, state, result, failure, visibleResult);
        else state.finishTask = platform.scheduler().delayed(delay,
                () -> completeProcessingResult(uniqueId, state, result, failure, visibleResult));
    }

    private void completeProcessingResult(UUID uniqueId, ProcessingState state, AuthResult result,
                                          Throwable failure, CompletableFuture<AuthResult> visibleResult) {
        processingOperations.remove(uniqueId, state);
        state.releaseNaturally();
        if (failure == null) visibleResult.complete(result);
        else visibleResult.completeExceptionally(failure);
    }

    private ProcessingState startProcessingTitle(UUID playerId, ProcessingPresentation presentation) {
        if (!presentation.settings.enabled()) return new ProcessingState(null, null);
        ProcessingTitleSettings.Timings timings = presentation.settings.timings();
        TitleHandle title = display.title(playerId, "pnauth:auth-processing", new TitleOptions(
                presentation.title(0), presentation.subtitle, timings.fadeIn(),
                timings.stay(), timings.fadeOut(), java.time.Duration.ZERO,
                java.time.Duration.ZERO));
        TaskHandle animation = null;
        if (presentation.settings.animation().type() == ProcessingTitleSettings.Type.GRADIENT
                && presentation.settings.animation().frameCount() > 1) {
            animation = platform.player(playerId).map(player -> platform.scheduler().repeating(player,
                    timings.frameInterval(), timings.frameInterval(), new Runnable() {
                        private int frame = 1;
                        @Override public void run() {
                            if (title.active()) title.title(presentation.title(frame++));
                        }
                    })).orElse(null);
        }
        return new ProcessingState(title, animation);
    }

    private record ProcessingPresentation(ProcessingTitleSettings settings, MessageFormat format, String title, String subtitle,
                                          String successTitle, String successSubtitle, String failureTitle, String failureSubtitle) {
        private static ProcessingPresentation disabled() {
            return new ProcessingPresentation(new ProcessingTitleSettings(false,
                    new ProcessingTitleSettings.Animation(ProcessingTitleSettings.Type.NONE, java.util.List.of(), 1),
                    ProcessingTitleSettings.Timings.defaults()), MessageFormat.LEGACY, "", "", "", "", "", "");
        }
        private String title(int frame) {
            ProcessingTitleSettings.Animation animation = settings.animation();
            return animation.type() == ProcessingTitleSettings.Type.GRADIENT
                    ? AnimatedGradient.frame(title, format, animation.colors(), frame % animation.frameCount(), animation.frameCount())
                    : title;
        }
    }

    private static final class ProcessingState {
        private int references = 1;
        private final TitleHandle title;
        private final TaskHandle animation;
        private final long startedAtNanos = System.nanoTime();
        private TaskHandle finishTask;
        private ProcessingState(TitleHandle title, TaskHandle animation) { this.title = title; this.animation = animation; }
        private void close() {
            stopAnimation();
            if (finishTask != null) finishTask.cancel();
            if (title != null) title.close();
        }
        private void releaseNaturally() {
            if (animation != null) animation.cancel();
            if (finishTask != null) finishTask.cancel();
            if (title != null) title.release();
        }
        private void stopAnimation() { if (animation != null) animation.cancel(); }
    }

    @Override
    public PnPlatform platform() {
        return platform;
    }

    /** Installs the player facade supplied by the active server adapter. */
    public void installPlatform(PnPlatform platform) {
        this.platform = java.util.Objects.requireNonNull(platform, "platform");
    }

    @Override
    public ServiceRegistry services() {
        return services;
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
            events.publish(new DialogPreferenceChangedEvent(uniqueId, record.realName(), selected));
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
        platform.tasks().cancelAll();
        platform.players().forEach(player -> platform.dialogs().closeAll(player.uniqueId()));
        processingOperations.values().forEach(ProcessingState::close);
        processingOperations.clear();
        executor.shutdownNow();
        repository.close();
        sessions.clear();
        joining.clear();
        failedAttempts.clear();
        failedTotpAttempts.clear();
        pendingTotpSetups.clear();
        verificationContinuations.clear();
        verificationDisplays.values().forEach(handles -> handles.forEach(DisplayHandle::close));
        verificationDisplays.clear();
        bans.clear();
    }

    private AuthResult failedLogin(UUID attemptKey, Session session) {
        AttemptState state = failedAttempts.computeIfAbsent(attemptKey, ignored -> new AttemptState());
        synchronized (state) {
            state.failures++;
            if (state.failures >= settings.maxLoginAttempts()) {
                state.lockedUntil = clock.millis() + settings.lockoutDuration().toMillis();
                if (features.banOnFailedLogin()) {
                    bans.ban(session.ip, features.banDuration());
                }
                return AuthResult.LOCKED_OUT;
            }
            return AuthResult.INVALID_PASSWORD;
        }
    }

    private AuthResult confirmPendingTotpSetup(UUID uniqueId, String code) {
        PendingTotpSetup pending = pendingTotpSetups.get(uniqueId);
        Session session = sessions.get(uniqueId);
        if (pending == null || session == null || session.record == null || session.status != AuthStatus.AUTHENTICATED
                || pending.generation != session.generation || pending.expiresAt <= clock.millis()) {
            if (pending != null) pendingTotpSetups.remove(uniqueId, pending);
            return AuthResult.TOTP_SETUP_REQUIRED;
        }
        synchronized (pending) {
            if (pendingTotpSetups.get(uniqueId) != pending) return AuthResult.TOTP_SETUP_REQUIRED;
            if (!totp.verify(pending.setup.secret(), code)) return failedTotp(attemptKey(uniqueId, session), session);
            String encryptedSecret = totp.encrypt(pending.setup.secret());
            totp.replaceTotpData(uniqueId, encryptedSecret, pending.setup.recoveryCodes());
            AuthRecord record = withTotp(session.record, encryptedSecret);
            replaceSession(uniqueId, session, new Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip));
            pendingTotpSetups.remove(uniqueId, pending);
            events.publish(new TotpStateChangedEvent(uniqueId, record.realName(), true));
            return AuthResult.TOTP_ENABLED;
        }
    }

    private boolean isTotpLocked(UUID uniqueId) {
        AttemptState state = failedTotpAttempts.get(uniqueId);
        if (state == null) return false;
        synchronized (state) {
            if (state.lockedUntil != 0 && state.lockedUntil <= clock.millis()) {
                failedTotpAttempts.remove(uniqueId, state);
                return false;
            }
            return state.lockedUntil != 0;
        }
    }

    private AuthResult failedTotp(UUID attemptKey, Session session) {
        AttemptState state = failedTotpAttempts.computeIfAbsent(attemptKey, ignored -> new AttemptState());
        synchronized (state) {
            state.failures++;
            if (state.failures >= features.totpMaxAttempts()) {
                state.lockedUntil = clock.millis() + features.totpLockoutDuration().toMillis();
                if (features.banOnFailedLogin()) {
                    bans.ban(session.ip, features.banDuration());
                }
                return AuthResult.LOCKED_OUT;
            }
            return AuthResult.TOTP_INVALID;
        }
    }

    private boolean verifyTotpOrRecoveryCode(UUID uniqueId, String encryptedSecret, String code) {
        try {
            if (encryptedSecret != null && !encryptedSecret.isBlank()
                    && totp.verify(totp.decrypt(encryptedSecret), code)) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // A damaged encrypted secret must not make recovery codes unusable.
        }
        return totp.consumeRecoveryCode(uniqueId, code);
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

    private CompletableFuture<AuthResult> guarded(
            AuthOperation operation, UUID uniqueId, Supplier<AuthResult> operationBody
    ) {
        Session session = uniqueId == null ? null : sessions.get(uniqueId);
        String username = session == null || session.record == null ? "" : session.record.realName();
        String ip = session == null ? null : session.ip;
        return guarded(AuthOperationContext.user(operation, uniqueId, username, ip), operationBody);
    }

    private CompletableFuture<AuthResult> guarded(
            AuthOperationContext context, Supplier<AuthResult> operationBody
    ) {
        PreAuthOperationEvent preEvent = new PreAuthOperationEvent(context);
        events.publish(preEvent);
        if (preEvent.cancelled()) {
            return completedOperation(context, AuthResult.OPERATION_DENIED);
        }
        return extensions.evaluate(context).thenCompose(decision -> {
            if (decision.type() == AuthPolicyDecision.Type.DENY) {
                return completedOperation(context, AuthResult.OPERATION_DENIED);
            }
            if (decision.type() == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) {
                return completedOperation(context, AuthResult.ADDITIONAL_VERIFICATION_REQUIRED);
            }
            return async(operationBody).thenApply(result -> finishOperation(context, result));
        }).toCompletableFuture();
    }

    private CompletableFuture<AuthResult> completedOperation(AuthOperationContext context, AuthResult result) {
        finishOperation(context, result);
        return CompletableFuture.completedFuture(result);
    }

    private AuthResult finishOperation(AuthOperationContext context, AuthResult result) {
        events.publish(new AuthOperationCompletedEvent(context, result));
        publishAuthenticationAttempt(context, result);
        return result;
    }

    private <T> CompletableFuture<T> guardedValue(AuthOperationContext context, Supplier<T> operationBody) {
        PreAuthOperationEvent preEvent = new PreAuthOperationEvent(context);
        events.publish(preEvent);
        if (preEvent.cancelled()) return CompletableFuture.failedFuture(new AuthOperationRejectedException(
                AuthResult.OPERATION_DENIED, preEvent.cancellationReason()));
        return extensions.evaluate(context).thenCompose(decision -> {
            if (decision.type() == AuthPolicyDecision.Type.DENY) {
                return CompletableFuture.<T>failedFuture(new AuthOperationRejectedException(
                        AuthResult.OPERATION_DENIED, decision.message()));
            }
            if (decision.type() == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) {
                return CompletableFuture.<T>failedFuture(new AuthOperationRejectedException(
                        AuthResult.ADDITIONAL_VERIFICATION_REQUIRED, decision.message()));
            }
            return async(operationBody);
        }).toCompletableFuture();
    }

    private void publishAuthenticationAttempt(AuthOperationContext context, AuthResult result) {
        if (context.uniqueId() != null && (context.operation() == AuthOperation.LOGIN
                || context.operation() == AuthOperation.TOTP_VERIFY)) {
            events.publish(new AuthenticationAttemptEvent(context.uniqueId(), context.username(),
                    context.operation(), result));
        }
    }

    private void attachContinuation(UUID uniqueId, Runnable continuation) {
        extensions.pending(uniqueId).ifPresent(ticket -> verificationContinuations.put(ticket.id(), continuation));
    }

    private void completeVerifiedAuthentication(UUID uniqueId, Session expected, AuthOperationContext context,
                                                UserAuthenticatedEvent.Cause cause) {
        if (extensions.evaluate(context).toCompletableFuture().join().type() != AuthPolicyDecision.Type.ALLOW) return;
        Session current = sessions.get(uniqueId);
        if (current == null || current.generation != expected.generation || current.status == AuthStatus.AUTHENTICATED) return;
        AuthResult result = authenticate(uniqueId, current, cause);
        finishOperation(context, result);
    }

    private AuthResult authenticate(UUID uniqueId, Session session, UserAuthenticatedEvent.Cause cause) {
        failedAttempts.remove(attemptKey(uniqueId, session));
        AuthRecord identity = session.record.uniqueId().equals(uniqueId)
                ? session.record : reassignIdentity(session.record, uniqueId);
        long now = clock.millis();
        repository.updateLastLogin(uniqueId, now);
        if (session.ip != null) repository.updateLastIp(uniqueId, session.ip);
        AuthRecord record = new AuthRecord(
                identity.uniqueId(), identity.username(), identity.realName(),
                identity.passwordHash(), identity.registeredAt(), now,
                identity.premium(), identity.registeredIp(), session.ip, identity.totpSecret(),
                identity.dialogPreference()
        );
        replaceSession(uniqueId, session, new Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip));
        events.publish(new UserAuthenticatedEvent(uniqueId, record.realName(), cause));
        return AuthResult.SUCCESS;
    }

    private Session upgradePasswordHash(UUID uniqueId, Session session, String password) {
        if (!PasswordHasher.needsRehash(session.record.passwordHash(), settings)) {
            return session;
        }
        PasswordHash upgraded = PasswordHasher.hash(password, settings);
        repository.updatePassword(session.record.uniqueId(), upgraded);
        Session replacement = new Session(
                session.generation,
                session.record.withPasswordHash(upgraded),
                session.status,
                session.ip
        );
        return sessions.replace(uniqueId, session, replacement) ? replacement : session;
    }

    private AuthRecord reassignIdentity(AuthRecord record, UUID uniqueId) {
        if (!repository.reassignUniqueId(record.uniqueId(), uniqueId)) {
            throw new IllegalStateException("Could not bind account to the current player UUID");
        }
        return new AuthRecord(
                uniqueId, record.username(), record.realName(), record.passwordHash(), record.registeredAt(),
                record.lastLoginAt(), record.premium(), record.registeredIp(), record.lastIp(), record.totpSecret(),
                record.dialogPreference()
        );
    }

    private static AuthRecord withTotp(AuthRecord record, String secret) {
        return new AuthRecord(
                record.uniqueId(), record.username(), record.realName(), record.passwordHash(), record.registeredAt(),
                record.lastLoginAt(), record.premium(), record.registeredIp(), record.lastIp(), secret,
                record.dialogPreference()
        );
    }

    private static AuthRecord withLastIp(AuthRecord record, String lastIp) {
        return new AuthRecord(
                record.uniqueId(), record.username(), record.realName(), record.passwordHash(), record.registeredAt(),
                record.lastLoginAt(), record.premium(), record.registeredIp(), lastIp, record.totpSecret(),
                record.dialogPreference()
        );
    }

    private static UUID attemptKey(UUID uniqueId, Session session) {
        return session.record == null ? uniqueId : session.record.uniqueId();
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

    private record PendingTotpSetup(long generation, TotpSetup setup, long expiresAt) {
    }

    private record LoginPreparation(Session session, AuthResult result) {
    }

    private record TotpPreparation(Session session, AuthResult result) {
    }

    private static final class AttemptState {
        private int failures;
        private long lockedUntil;
    }
}
