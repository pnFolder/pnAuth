package ru.privatenull.pnauth.service

import ru.privatenull.pnauth.api.AdmissionDecision
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthResult
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.api.AuthUser
import ru.privatenull.pnauth.api.DialogPreference
import ru.privatenull.pnauth.api.TotpSetup
import ru.privatenull.pnauth.config.AuthSettings
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.display.ActionBarOptions
import ru.privatenull.pnauth.display.BossBarColor
import ru.privatenull.pnauth.display.BossBarOptions
import ru.privatenull.pnauth.display.BossBarOverlay
import ru.privatenull.pnauth.display.DisplayHandle
import ru.privatenull.pnauth.display.NoopPlayerDisplay
import ru.privatenull.pnauth.display.PlayerDisplay
import ru.privatenull.pnauth.event.AdmissionEvaluatedEvent
import ru.privatenull.pnauth.event.AuthEventBus
import ru.privatenull.pnauth.event.AuthOperationCompletedEvent
import ru.privatenull.pnauth.event.AuthenticationAttemptEvent
import ru.privatenull.pnauth.event.DialogPreferenceChangedEvent
import ru.privatenull.pnauth.event.PasswordChangedEvent
import ru.privatenull.pnauth.event.PreAuthOperationEvent
import ru.privatenull.pnauth.event.PremiumStateChangedEvent
import ru.privatenull.pnauth.event.SimpleAuthEventBus
import ru.privatenull.pnauth.event.TotpSetupStartedEvent
import ru.privatenull.pnauth.event.TotpStateChangedEvent
import ru.privatenull.pnauth.event.UserAuthenticatedEvent
import ru.privatenull.pnauth.event.UserJoinedEvent
import ru.privatenull.pnauth.event.UserLoggedOutEvent
import ru.privatenull.pnauth.event.UserQuitEvent
import ru.privatenull.pnauth.event.UserRegisteredEvent
import ru.privatenull.pnauth.event.UserUnregisteredEvent
import ru.privatenull.pnauth.event.VerificationRequiredEvent
import ru.privatenull.pnauth.event.VerificationResolvedEvent
import ru.privatenull.pnauth.extension.AuthExtensionRegistry
import ru.privatenull.pnauth.extension.AuthOperation
import ru.privatenull.pnauth.extension.AuthOperationContext
import ru.privatenull.pnauth.extension.AuthOperationRejectedException
import ru.privatenull.pnauth.extension.AuthPhase
import ru.privatenull.pnauth.extension.AuthPolicyDecision
import ru.privatenull.pnauth.extension.DefaultAuthExtensionRegistry
import ru.privatenull.pnauth.extension.VerificationTicket
import ru.privatenull.pnauth.kernel.service.DefaultServiceRegistry
import ru.privatenull.pnauth.kernel.service.ServiceRegistry
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.UnavailablePlatform
import ru.privatenull.pnauth.security.IpBanStore
import ru.privatenull.pnauth.security.PasswordHasher
import ru.privatenull.pnauth.security.TotpService
import ru.privatenull.pnauth.storage.AuthRecord
import ru.privatenull.pnauth.storage.AuthRepository
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

class AuthService internal constructor(
    private val repository: AuthRepository,
    private val settings: AuthSettings,
    private val totp: TotpService,
    private val features: FeatureSettings,
    private val clock: Clock,
    events: AuthEventBus,
    extensions: AuthExtensionRegistry
) : AuthApi {

    private val workerThreadIndex = AtomicLong()
    private val executor = Executors.newFixedThreadPool(workerThreads()) { runnable ->
        Thread(runnable, "pnauth-worker-" + workerThreadIndex.incrementAndGet()).apply { isDaemon = true }
    }
    private val generations = AtomicLong()
    private val joining: ConcurrentMap<UUID, Long> = ConcurrentHashMap()
    private val sessions: ConcurrentMap<UUID, Session> = ConcurrentHashMap()
    private val failedAttempts: ConcurrentMap<UUID, AttemptState> = ConcurrentHashMap()
    private val failedTotpAttempts: ConcurrentMap<UUID, AttemptState> = ConcurrentHashMap()
    private val pendingTotpSetups: ConcurrentMap<UUID, PendingTotpSetup> = ConcurrentHashMap()
    private val verificationContinuations: ConcurrentMap<String, Runnable> = ConcurrentHashMap()
    private val bans = IpBanStore()
    private val eventsBus: AuthEventBus = Objects.requireNonNull(events, "events")
    private val extensionRegistry: AuthExtensionRegistry = Objects.requireNonNull(extensions, "extensions")
    @Volatile
    private var display: PlayerDisplay = NoopPlayerDisplay()
    @Volatile
    private var platform: Platform = UnavailablePlatform()
    private val verificationDisplays: ConcurrentMap<String, List<DisplayHandle>> = ConcurrentHashMap()
    private val services: ServiceRegistry = DefaultServiceRegistry()

    @JvmOverloads
    constructor(
        repository: AuthRepository,
        settings: AuthSettings,
        totp: TotpService = TotpService(repository, randomKey()),
        features: FeatureSettings = FeatureSettings.defaults(),
        events: AuthEventBus = SimpleAuthEventBus(),
        extensions: AuthExtensionRegistry = DefaultAuthExtensionRegistry()
    ) : this(repository, settings, totp, features, Clock.systemUTC(), events, extensions)

    constructor(
        repository: AuthRepository,
        settings: AuthSettings,
        clock: Clock
    ) : this(repository, settings, TotpService(repository, randomKey()), FeatureSettings.defaults(), clock, SimpleAuthEventBus())

    internal constructor(
        repository: AuthRepository,
        settings: AuthSettings,
        totp: TotpService,
        features: FeatureSettings,
        clock: Clock
    ) : this(repository, settings, totp, features, clock, SimpleAuthEventBus())

    internal constructor(
        repository: AuthRepository,
        settings: AuthSettings,
        totp: TotpService,
        features: FeatureSettings,
        clock: Clock,
        events: AuthEventBus
    ) : this(repository, settings, totp, features, clock, events, DefaultAuthExtensionRegistry())

    init {
        this.extensionRegistry.onTicket { ticket ->
            if (ticket.status == VerificationTicket.Status.PENDING) {
                this.eventsBus.publish(VerificationRequiredEvent(ticket))
                if (ticket.uniqueId != null) {
                    val remaining = Duration.between(Instant.now(), ticket.expiresAt)
                    verificationDisplays[ticket.id] = listOf(
                        display.actionBar(
                            ticket.uniqueId, "pnauth:verification:action:" + ticket.id, ActionBarOptions(
                                ticket.message, Duration.ofSeconds(1), remaining
                            )
                        ),
                        display.bossBar(
                            ticket.uniqueId, "pnauth:verification:boss:" + ticket.id, BossBarOptions(
                                ticket.message, 1f, BossBarColor.PURPLE, BossBarOverlay.PROGRESS,
                                false, false, false, remaining
                            )
                        )
                    )
                }
            } else {
                this.eventsBus.publish(VerificationResolvedEvent(ticket))
                val handles = verificationDisplays.remove(ticket.id)
                handles?.forEach { it.close() }
                val continuation = verificationContinuations.remove(ticket.id)
                if (ticket.status == VerificationTicket.Status.APPROVED && continuation != null) {
                    executor.execute(continuation)
                }
            }
        }
    }

    override fun onJoin(uniqueId: UUID, username: String): CompletableFuture<AuthStatus> {
        return onJoin(uniqueId, username, null)
    }

    override fun onJoin(uniqueId: UUID, username: String, ip: String?): CompletableFuture<AuthStatus> {
        val generation = generations.incrementAndGet()
        joining[uniqueId] = generation
        val normalizedUsername = normalizeUsername(username)
        return async {
            var found = repository.findByUniqueId(uniqueId)
            if (found.isEmpty && normalizedUsername.isNotEmpty()) {
                found = repository.findByUsername(normalizedUsername)
            }
            var record = found.orElse(null)
            if (record != null && record.username != normalizedUsername) {
                repository.updateUsername(uniqueId, normalizedUsername)
                record = AuthRecord(
                    record.uniqueId,
                    normalizedUsername,
                    username,
                    record.passwordHash,
                    record.registeredAt,
                    record.lastLoginAt,
                    record.premium,
                    record.registeredIp,
                    record.lastIp,
                    record.totpSecret,
                    record.dialogPreference
                )
            }
            var loaded = record
            val hasTotp = loaded != null && features.totpEnabled &&
                    !loaded.totpSecret.isNullOrBlank()
            val trustedPremiumLogin = loaded != null && loaded.premium && features.premiumEnabled
            val lastLoginAge = if (loaded == null || loaded.lastLoginAt == null) Long.MAX_VALUE else clock.millis() - loaded.lastLoginAt!!
            val trustedIpSession = loaded != null && features.restoreSessionOnSameIp &&
                    ip != null && ip == loaded.lastIp && loaded.lastLoginAt != null &&
                    lastLoginAge >= 0 && lastLoginAge <= features.sessionLifetime.toMillis()
            val sessionValid = trustedPremiumLogin || trustedIpSession
            val status = if (loaded == null) {
                AuthStatus.UNREGISTERED
            } else if (sessionValid) {
                if (hasTotp) AuthStatus.TOTP_PENDING else AuthStatus.AUTHENTICATED
            } else {
                AuthStatus.UNAUTHENTICATED
            }

            if (sessionValid && loaded != null && loaded.uniqueId != uniqueId) {
                loaded = reassignIdentity(loaded, uniqueId)
            }
            if (joining[uniqueId] != null && joining[uniqueId] == generation) {
                sessions[uniqueId] = Session(generation, loaded, status, ip)
                eventsBus.publish(UserJoinedEvent(uniqueId, username, ip ?: "", status))
                if (status == AuthStatus.AUTHENTICATED) {
                    eventsBus.publish(
                        UserAuthenticatedEvent(
                            uniqueId, username,
                            if (trustedPremiumLogin) UserAuthenticatedEvent.Cause.PREMIUM else UserAuthenticatedEvent.Cause.SESSION
                        )
                    )
                }
            }
            status
        }
    }

    override fun onQuit(uniqueId: UUID) {
        joining.remove(uniqueId)
        val departed = sessions.remove(uniqueId)
        pendingTotpSetups.remove(uniqueId)
        display.clear(uniqueId)
        platform.tasks().cancelAll(uniqueId)
        platform.dialogs().closeAll(uniqueId)
        eventsBus.publish(UserQuitEvent(uniqueId, departed?.record?.realName() ?: ""))
    }

    override fun register(
        uniqueId: UUID,
        username: String,
        password: String,
        confirmation: String
    ): CompletableFuture<AuthResult> {
        val registrationSession = sessions[uniqueId]
        return guarded(
            AuthOperationContext(
                AuthOperation.REGISTER, uniqueId, username,
                registrationSession?.ip, emptyMap()
            )
        ) {
            val session = sessions[uniqueId] ?: return@guarded AuthResult.NOT_JOINED
            if (session.record != null) return@guarded AuthResult.ALREADY_REGISTERED
            if (!settings.isPasswordValid(password)) return@guarded AuthResult.INVALID_PASSWORD_FORMAT
            if (password != confirmation) return@guarded AuthResult.PASSWORDS_DO_NOT_MATCH

            val normalizedUsername = normalizeUsername(username)
            if (!settings.isUsernameValid(normalizedUsername)) return@guarded AuthResult.INVALID_USERNAME
            if (repository.findByUsername(normalizedUsername).isPresent) return@guarded AuthResult.USERNAME_TAKEN

            val now = clock.millis()
            val passwordHash = PasswordHasher.hash(password, settings)
            val record = AuthRecord(
                uniqueId, normalizedUsername, username, passwordHash, now, now, false, session.ip, session.ip, null
            )
            if (!repository.create(record)) {
                return@guarded if (repository.findByUniqueId(uniqueId).isPresent) AuthResult.ALREADY_REGISTERED else AuthResult.USERNAME_TAKEN
            }
            replaceSession(uniqueId, session, Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip))
            eventsBus.publish(UserRegisteredEvent(uniqueId, record.realName(), session.ip ?: "", false))
            eventsBus.publish(UserAuthenticatedEvent(uniqueId, record.realName(), UserAuthenticatedEvent.Cause.REGISTER))
            AuthResult.SUCCESS
        }
    }

    override fun login(uniqueId: UUID, password: String): CompletableFuture<AuthResult> {
        val initial = sessions[uniqueId]
        val request = AuthOperationContext(
            AuthOperation.LOGIN, AuthPhase.REQUEST, uniqueId,
            initial?.record?.realName() ?: "",
            initial?.ip, emptyMap()
        )
        val preEvent = PreAuthOperationEvent(request)
        eventsBus.publish(preEvent)
        if (preEvent.cancelled()) return completedOperation(request, AuthResult.OPERATION_DENIED)
        return async {
            var session = sessions[uniqueId] ?: return@async LoginPreparation(null, AuthResult.NOT_JOINED)
            val record = session.record ?: return@async LoginPreparation(null, AuthResult.NOT_REGISTERED)
            if (session.status == AuthStatus.AUTHENTICATED) return@async LoginPreparation(null, AuthResult.ALREADY_AUTHENTICATED)
            val attemptKey = attemptKey(uniqueId, session)
            if (isLocked(attemptKey)) return@async LoginPreparation(null, AuthResult.LOCKED_OUT)
            if (password.length > settings.maxPasswordLength ||
                !PasswordHasher.matches(password, record.passwordHash())
            ) {
                return@async LoginPreparation(null, failedLogin(attemptKey, session))
            }

            session = upgradePasswordHash(uniqueId, session, password)
            val currentRecord = session.record ?: record

            if (features.totpEnabled && !currentRecord.totpSecret().isNullOrBlank()) {
                replaceSession(uniqueId, session, Session(session.generation, currentRecord, AuthStatus.TOTP_PENDING, session.ip))
                return@async LoginPreparation(null, AuthResult.TOTP_REQUIRED)
            }
            LoginPreparation(session, null)
        }.thenCompose { prepared ->
            if (prepared.result != null) return@thenCompose completedOperation(request, prepared.result)
            val verified = request.at(AuthPhase.CREDENTIAL_VERIFIED)
            extensionRegistry.evaluate(verified).thenCompose { decision ->
                if (decision.type == AuthPolicyDecision.Type.DENY) {
                    return@thenCompose completedOperation(verified, AuthResult.OPERATION_DENIED)
                }
                if (decision.type == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) {
                    attachContinuation(uniqueId) {
                        completeVerifiedAuthentication(
                            uniqueId, prepared.session!!, verified, UserAuthenticatedEvent.Cause.PASSWORD
                        )
                    }
                    return@thenCompose completedOperation(verified, AuthResult.ADDITIONAL_VERIFICATION_REQUIRED)
                }
                async { authenticate(uniqueId, prepared.session!!, UserAuthenticatedEvent.Cause.PASSWORD) }
                    .thenApply { result -> finishOperation(verified, result) }
            }
        }.toCompletableFuture()
    }

    override fun logout(uniqueId: UUID): CompletableFuture<AuthResult> {
        return guarded(AuthOperation.LOGOUT, uniqueId) {
            val session = sessions[uniqueId] ?: return@guarded AuthResult.NOT_JOINED
            if (session.status != AuthStatus.AUTHENTICATED || session.record == null) return@guarded AuthResult.NOT_AUTHENTICATED
            pendingTotpSetups.remove(uniqueId)
            repository.updateLastIp(session.record.uniqueId(), null)
            val record = withLastIp(session.record, null)
            replaceSession(uniqueId, session, Session(session.generation, record, AuthStatus.UNAUTHENTICATED, session.ip))
            eventsBus.publish(UserLoggedOutEvent(uniqueId, record.realName()))
            AuthResult.SUCCESS
        }
    }

    override fun changePassword(uniqueId: UUID, oldPassword: String, newPassword: String): CompletableFuture<AuthResult> {
        return guarded(AuthOperation.CHANGE_PASSWORD, uniqueId) {
            val session = sessions[uniqueId] ?: return@guarded AuthResult.NOT_JOINED
            if (session.status != AuthStatus.AUTHENTICATED || session.record == null) return@guarded AuthResult.NOT_AUTHENTICATED
            if (!PasswordHasher.matches(oldPassword, session.record.passwordHash())) {
                return@guarded AuthResult.INVALID_PASSWORD
            }
            if (!settings.isPasswordValid(newPassword)) return@guarded AuthResult.INVALID_PASSWORD_FORMAT
            val passwordHash = PasswordHasher.hash(newPassword, settings)
            repository.updatePassword(uniqueId, passwordHash)
            val record = AuthRecord(
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
            )
            replaceSession(uniqueId, session, Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip))
            eventsBus.publish(PasswordChangedEvent(uniqueId, record.realName(), false))
            AuthResult.SUCCESS
        }
    }

    override fun beginTotpSetup(uniqueId: UUID, password: String, issuer: String): CompletableFuture<TotpSetup> {
        val current = sessions[uniqueId]
        return guardedValue(
            AuthOperationContext.user(
                AuthOperation.TOTP_SETUP, uniqueId,
                current?.record?.realName() ?: "",
                current?.ip
            )
        ) {
            val session = sessions[uniqueId]
            if (session == null || session.record == null || session.status != AuthStatus.AUTHENTICATED) {
                throw IllegalStateException("Player is not authenticated")
            }
            if (!features.totpEnabled) {
                throw IllegalStateException("TOTP is disabled")
            }
            if (!session.record.totpSecret().isNullOrBlank()) {
                throw IllegalStateException("TOTP is already enabled")
            }
            if (password.isBlank() || password.length > settings.maxPasswordLength ||
                !PasswordHasher.matches(password, session.record.passwordHash())
            ) {
                throw IllegalStateException("Invalid password")
            }
            val secret = totp.generateSecret()
            val setup = TotpSetup(
                secret,
                totp.provisioningUri(issuer, session.record.realName(), secret),
                totp.generateRecoveryCodes(features.recoveryCodesAmount)
            )
            pendingTotpSetups[uniqueId] = PendingTotpSetup(
                session.generation, setup, clock.millis() + features.totpSetupLifetime.toMillis()
            )
            eventsBus.publish(TotpSetupStartedEvent(uniqueId, session.record.realName()))
            setup
        }
    }

    override fun confirmTotpSetup(uniqueId: UUID, code: String): CompletableFuture<AuthResult> {
        return guarded(AuthOperation.TOTP_VERIFY, uniqueId) { confirmPendingTotpSetup(uniqueId, code) }
    }

    override fun verifyTotp(uniqueId: UUID, code: String): CompletableFuture<AuthResult> {
        if (pendingTotpSetups.containsKey(uniqueId)) return confirmTotpSetup(uniqueId, code)
        val initial = sessions[uniqueId]
        val request = AuthOperationContext(
            AuthOperation.TOTP_VERIFY, AuthPhase.REQUEST, uniqueId,
            initial?.record?.realName() ?: "",
            initial?.ip, emptyMap()
        )
        val preEvent = PreAuthOperationEvent(request)
        eventsBus.publish(preEvent)
        if (preEvent.cancelled()) return completedOperation(request, AuthResult.OPERATION_DENIED)
        return async {
            val session = sessions[uniqueId] ?: return@async TotpPreparation(null, AuthResult.NOT_JOINED)
            if (session.record == null) return@async TotpPreparation(null, AuthResult.NOT_JOINED)
            if (session.status != AuthStatus.TOTP_PENDING) return@async TotpPreparation(null, AuthResult.TOTP_NOT_ENABLED)
            val attemptKey = attemptKey(uniqueId, session)
            if (isTotpLocked(attemptKey)) return@async TotpPreparation(null, AuthResult.LOCKED_OUT)
            val valid = verifyTotpOrRecoveryCode(session.record.uniqueId(), session.record.totpSecret(), code)
            if (!valid) {
                return@async TotpPreparation(null, failedTotp(attemptKey, session))
            }
            failedTotpAttempts.remove(attemptKey)
            TotpPreparation(session, null)
        }.thenCompose { prepared ->
            if (prepared.result != null) return@thenCompose completedOperation(request, prepared.result)
            val verified = request.at(AuthPhase.CREDENTIAL_VERIFIED)
            extensionRegistry.evaluate(verified).thenCompose { decision ->
                if (decision.type == AuthPolicyDecision.Type.DENY) return@thenCompose completedOperation(verified, AuthResult.OPERATION_DENIED)
                if (decision.type == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) {
                    attachContinuation(uniqueId) {
                        completeVerifiedAuthentication(
                            uniqueId, prepared.session!!, verified, UserAuthenticatedEvent.Cause.TOTP
                        )
                    }
                    return@thenCompose completedOperation(verified, AuthResult.ADDITIONAL_VERIFICATION_REQUIRED)
                }
                async { authenticate(uniqueId, prepared.session!!, UserAuthenticatedEvent.Cause.TOTP) }
                    .thenApply { result -> finishOperation(verified, result) }
            }
        }.toCompletableFuture()
    }

    override fun disableTotp(uniqueId: UUID, password: String, code: String): CompletableFuture<AuthResult> {
        return guarded(AuthOperation.TOTP_DISABLE, uniqueId) {
            val session = sessions[uniqueId] ?: return@guarded AuthResult.NOT_JOINED
            if (session.record == null) return@guarded AuthResult.NOT_JOINED
            if (session.status != AuthStatus.AUTHENTICATED) return@guarded AuthResult.NOT_AUTHENTICATED
            if (session.record.totpSecret().isNullOrBlank()) return@guarded AuthResult.TOTP_NOT_ENABLED
            val attemptKey = attemptKey(uniqueId, session)
            if (isTotpLocked(attemptKey)) return@guarded AuthResult.LOCKED_OUT
            if (password.isBlank() || password.length > settings.maxPasswordLength ||
                !PasswordHasher.matches(password, session.record.passwordHash())
            ) {
                return@guarded AuthResult.INVALID_PASSWORD
            }
            if (!verifyTotpOrRecoveryCode(session.record.uniqueId(), session.record.totpSecret(), code)) {
                return@guarded failedTotp(attemptKey, session)
            }
            totp.clearTotpData(session.record.uniqueId())
            failedTotpAttempts.remove(attemptKey)
            replaceSession(uniqueId, session, Session(session.generation, withTotp(session.record, null), session.status, session.ip))
            eventsBus.publish(TotpStateChangedEvent(uniqueId, session.record.realName(), false))
            AuthResult.TOTP_DISABLED
        }
    }

    override fun status(uniqueId: UUID): AuthStatus {
        val session = sessions[uniqueId]
        return session?.status ?: AuthStatus.NOT_LOADED
    }

    override fun user(uniqueId: UUID): Optional<AuthUser> {
        val session = sessions[uniqueId]
        return if (session?.record == null) Optional.empty() else Optional.of(session.record.toApiUser())
    }

    override fun isAuthenticated(uniqueId: UUID): Boolean {
        return status(uniqueId) == AuthStatus.AUTHENTICATED
    }

    override fun isPremium(username: String): CompletableFuture<Boolean> {
        return async {
            repository.findByUsername(normalizeUsername(username))
                .map { it.premium() }
                .orElse(false)
        }
    }

    override fun unregister(username: String): CompletableFuture<AuthResult> {
        return guarded(
            AuthOperationContext(AuthOperation.ADMIN_UNREGISTER, null, username, null, emptyMap())
        ) {
            val record = repository.findByUsername(normalizeUsername(username)).orElse(null)
                ?: return@guarded AuthResult.PLAYER_NOT_FOUND
            repository.deleteByUniqueId(record.uniqueId)
            sessions.remove(record.uniqueId)
            joining.remove(record.uniqueId)
            pendingTotpSetups.remove(record.uniqueId)
            failedAttempts.remove(record.uniqueId)
            failedTotpAttempts.remove(record.uniqueId)
            eventsBus.publish(UserUnregisteredEvent(record.uniqueId, record.realName, true))
            AuthResult.SUCCESS
        }
    }

    override fun unregister(uniqueId: UUID, password: String): CompletableFuture<AuthResult> {
        return guarded(AuthOperation.UNREGISTER, uniqueId) {
            val session = sessions[uniqueId] ?: return@guarded AuthResult.NOT_JOINED
            if (session.record == null) return@guarded AuthResult.NOT_JOINED
            if (session.status != AuthStatus.AUTHENTICATED) return@guarded AuthResult.NOT_AUTHENTICATED
            val attemptKey = attemptKey(uniqueId, session)
            if (isLocked(attemptKey)) return@guarded AuthResult.LOCKED_OUT
            if (password.length > settings.maxPasswordLength ||
                !PasswordHasher.matches(password, session.record.passwordHash)
            ) {
                return@guarded failedLogin(attemptKey, session)
            }
            repository.deleteByUniqueId(uniqueId)
            sessions.remove(uniqueId)
            joining.remove(uniqueId)
            pendingTotpSetups.remove(uniqueId)
            failedAttempts.remove(uniqueId)
            failedTotpAttempts.remove(uniqueId)
            eventsBus.publish(UserUnregisteredEvent(uniqueId, session.record.realName, false))
            AuthResult.SUCCESS
        }
    }

    override fun adminChangePassword(username: String, newPassword: String): CompletableFuture<AuthResult> {
        return guarded(
            AuthOperationContext(AuthOperation.ADMIN_CHANGE_PASSWORD, null, username, null, emptyMap())
        ) {
            val record = repository.findByUsername(normalizeUsername(username)).orElse(null)
                ?: return@guarded AuthResult.PLAYER_NOT_FOUND
            if (!settings.isPasswordValid(newPassword)) return@guarded AuthResult.INVALID_PASSWORD_FORMAT
            val passwordHash = PasswordHasher.hash(newPassword, settings)
            repository.updatePassword(record.uniqueId, passwordHash)
            val session = sessions[record.uniqueId]
            if (session != null) {
                val updated = AuthRecord(
                    record.uniqueId, record.username, record.realName, passwordHash, record.registeredAt,
                    record.lastLoginAt, record.premium, record.registeredIp, record.lastIp, record.totpSecret,
                    record.dialogPreference
                )
                replaceSession(record.uniqueId, session, Session(session.generation, updated, session.status, session.ip))
            }
            eventsBus.publish(PasswordChangedEvent(record.uniqueId, record.realName, true))
            AuthResult.SUCCESS
        }
    }

    override fun forceRegister(username: String, password: String): CompletableFuture<AuthResult> {
        return guarded(
            AuthOperationContext(AuthOperation.ADMIN_FORCE_REGISTER, null, username, null, emptyMap())
        ) {
            val normalizedUsername = normalizeUsername(username)
            if (!settings.isUsernameValid(normalizedUsername)) return@guarded AuthResult.INVALID_USERNAME
            if (!settings.isPasswordValid(password)) return@guarded AuthResult.INVALID_PASSWORD_FORMAT
            if (repository.findByUsername(normalizedUsername).isPresent) return@guarded AuthResult.ALREADY_REGISTERED

            val uniqueId = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8))
            if (repository.findByUniqueId(uniqueId).isPresent) return@guarded AuthResult.ALREADY_REGISTERED
            val record = AuthRecord(
                uniqueId, normalizedUsername, username, PasswordHasher.hash(password, settings), clock.millis(),
                null, false, null, null, null
            )
            if (!repository.create(record)) return@guarded AuthResult.ALREADY_REGISTERED
            eventsBus.publish(UserRegisteredEvent(uniqueId, record.realName(), sessionIpOrEmpty(uniqueId), true))
            AuthResult.SUCCESS
        }
    }

    override fun forceLogin(username: String): CompletableFuture<AuthResult> {
        return guarded(
            AuthOperationContext(AuthOperation.ADMIN_FORCE_LOGIN, null, username, null, emptyMap())
        ) {
            sessions.entries.stream()
                .filter { entry -> entry.value.record != null && entry.value.record!!.username.equals(username, ignoreCase = true) }
                .findFirst()
                .map { entry ->
                    if (entry.value.status == AuthStatus.AUTHENTICATED) {
                        AuthResult.ALREADY_AUTHENTICATED
                    } else {
                        authenticate(entry.key, entry.value, UserAuthenticatedEvent.Cause.ADMIN)
                    }
                }
                .orElse(AuthResult.PLAYER_NOT_FOUND)
        }
    }

    override fun togglePremium(username: String): CompletableFuture<AuthResult> {
        return guarded(
            AuthOperationContext(AuthOperation.PREMIUM_CHANGE, null, username, null, emptyMap())
        ) {
            repository.findByUsername(normalizeUsername(username))
                .map { record ->
                    val premium = !record.premium
                    repository.updatePremium(record.uniqueId, premium)
                    val session = sessions[record.uniqueId]
                    if (session != null) {
                        val updated = AuthRecord(
                            record.uniqueId, record.username, record.realName, record.passwordHash,
                            record.registeredAt, record.lastLoginAt, premium, record.registeredIp,
                            record.lastIp, record.totpSecret, record.dialogPreference
                        )
                        replaceSession(record.uniqueId, session, Session(session.generation, updated, session.status, session.ip))
                    }
                    eventsBus.publish(PremiumStateChangedEvent(record.uniqueId, record.realName, premium))
                    AuthResult.SUCCESS
                }
                .orElse(AuthResult.PLAYER_NOT_FOUND)
        }
    }

    override fun togglePremium(uniqueId: UUID): CompletableFuture<AuthResult> {
        val session = sessions[uniqueId]
        val username = session?.record?.realName ?: ""
        return guarded(
            AuthOperationContext(AuthOperation.PREMIUM_CHANGE, uniqueId, username, session?.ip, emptyMap())
        ) {
            repository.findByUniqueId(uniqueId)
                .map { record ->
                    val premium = !record.premium
                    repository.updatePremium(record.uniqueId, premium)
                    val currentSession = sessions[record.uniqueId]
                    if (currentSession != null) {
                        val updated = AuthRecord(
                            record.uniqueId, record.username, record.realName, record.passwordHash,
                            record.registeredAt, record.lastLoginAt, premium, record.registeredIp,
                            record.lastIp, record.totpSecret, record.dialogPreference
                        )
                        replaceSession(record.uniqueId, currentSession, Session(currentSession.generation, updated, currentSession.status, currentSession.ip))
                    }
                    eventsBus.publish(PremiumStateChangedEvent(record.uniqueId, record.realName, premium))
                    AuthResult.SUCCESS
                }
                .orElse(AuthResult.PLAYER_NOT_FOUND)
        }
    }

    override fun checkAdmission(username: String, ip: String, onlineAccountsFromIp: Int): CompletableFuture<AdmissionDecision> {
        val context = AuthOperationContext(
            AuthOperation.ADMISSION, null, username, ip,
            mapOf("onlineAccountsFromIp" to onlineAccountsFromIp.toString())
        )
        val preEvent = PreAuthOperationEvent(context)
        eventsBus.publish(preEvent)
        if (preEvent.cancelled()) {
            return completedAdmission(username, ip, AdmissionDecision(false, false, AdmissionDecision.Reason.POLICY_DENIED))
        }
        return extensionRegistry.evaluate(context).thenCompose { policy ->
            if (policy.type != AuthPolicyDecision.Type.ALLOW) {
                return@thenCompose completedAdmission(username, ip, AdmissionDecision(false, false, AdmissionDecision.Reason.POLICY_DENIED))
            }
            async {
                if (bans.isBanned(ip)) {
                    return@async AdmissionDecision(false, false, AdmissionDecision.Reason.BANNED)
                }
                if (!features.excludedIps.contains(ip) && onlineAccountsFromIp >= features.maxOnlineAccountsPerIp) {
                    return@async AdmissionDecision(false, false, AdmissionDecision.Reason.ONLINE_IP_LIMIT)
                }
                val record = repository.findByUsername(normalizeUsername(username)).orElse(null)
                if (record == null && !features.excludedIps.contains(ip) &&
                    repository.countRegisteredIp(ip) >= features.maxRegisteredAccountsPerIp
                ) {
                    return@async AdmissionDecision(false, false, AdmissionDecision.Reason.REGISTERED_IP_LIMIT)
                }
                AdmissionDecision(
                    true, record != null && record.premium && features.premiumEnabled,
                    AdmissionDecision.Reason.ALLOWED
                )
            }.thenApply { decision ->
                eventsBus.publish(AdmissionEvaluatedEvent(username, ip, decision))
                decision
            }
        }.toCompletableFuture()
    }

    private fun completedAdmission(username: String, ip: String, decision: AdmissionDecision): CompletableFuture<AdmissionDecision> {
        eventsBus.publish(AdmissionEvaluatedEvent(username, ip, decision))
        return CompletableFuture.completedFuture(decision)
    }

    override fun dialogPreference(uniqueId: UUID): DialogPreference {
        val session = sessions[uniqueId]
        return session?.record?.dialogPreference ?: DialogPreference.AUTO
    }

    override fun events(): AuthEventBus = eventsBus

    override fun extensions(): AuthExtensionRegistry = extensionRegistry

    override fun display(): PlayerDisplay = display

    fun installDisplay(display: PlayerDisplay) {
        this.display = Objects.requireNonNull(display, "display")
    }

    override fun platform(): Platform = platform

    /** Installs the player facade supplied by the active server adapter. */
    fun installPlatform(platform: Platform) {
        this.platform = Objects.requireNonNull(platform, "platform")
    }

    override fun services(): ServiceRegistry = services

    override fun setDialogPreference(uniqueId: UUID, preference: DialogPreference): CompletableFuture<AuthResult> {
        val selected = preference
        return async {
            if (!features.dialogs.allowPlayerPreference) return@async AuthResult.DIALOG_PREFERENCE_DISABLED
            val session = sessions[uniqueId] ?: return@async AuthResult.NOT_JOINED
            if (session.record == null) return@async AuthResult.NOT_JOINED
            repository.updateDialogPreference(uniqueId, selected)
            val record = AuthRecord(
                session.record.uniqueId, session.record.username, session.record.realName, session.record.passwordHash,
                session.record.registeredAt, session.record.lastLoginAt, session.record.premium, session.record.registeredIp,
                session.record.lastIp, session.record.totpSecret, selected
            )
            replaceSession(uniqueId, session, Session(session.generation, record, session.status, session.ip))
            eventsBus.publish(DialogPreferenceChangedEvent(uniqueId, record.realName, selected))
            AuthResult.DIALOG_PREFERENCE_UPDATED
        }
    }

    override fun shouldUseDialog(uniqueId: UUID, clientProtocol: Int, platformSupportsDialogs: Boolean): Boolean {
        if (!platformSupportsDialogs || !features.dialogs.enabled ||
            clientProtocol < features.dialogs.minClientProtocol
        ) return false
        return dialogPreference(uniqueId) != DialogPreference.DISABLED
    }

    override fun shouldUseCommandFallback(uniqueId: UUID, clientProtocol: Int, platformSupportsDialogs: Boolean): Boolean {
        if (shouldUseDialog(uniqueId, clientProtocol, platformSupportsDialogs)) return false
        return features.dialogs.fallbackToCommands
    }

    override fun close() {
        platform.tasks().cancelAll()
        platform.players().forEach { player -> platform.dialogs().closeAll(player.uniqueId()) }
        executor.shutdownNow()
        repository.close()
        sessions.clear()
        joining.clear()
        failedAttempts.clear()
        failedTotpAttempts.clear()
        pendingTotpSetups.clear()
        verificationContinuations.clear()
        verificationDisplays.values.forEach { handles -> handles.forEach(DisplayHandle::close) }
        verificationDisplays.clear()
        bans.clear()
    }

    private fun failedLogin(attemptKey: UUID, session: Session): AuthResult {
        val state = failedAttempts.computeIfAbsent(attemptKey) { AttemptState() }
        synchronized(state) {
            state.failures++
            if (state.failures >= settings.maxLoginAttempts) {
                state.lockedUntil = clock.millis() + settings.lockoutDuration.toMillis()
                if (features.banOnFailedLogin && session.ip != null) {
                    bans.ban(session.ip, features.banDuration)
                }
                return AuthResult.LOCKED_OUT
            }
            return AuthResult.INVALID_PASSWORD
        }
    }

    private fun confirmPendingTotpSetup(uniqueId: UUID, code: String?): AuthResult {
        val pending = pendingTotpSetups[uniqueId]
        val session = sessions[uniqueId]
        if (pending == null || session == null || session.record == null || session.status != AuthStatus.AUTHENTICATED ||
            pending.generation != session.generation || pending.expiresAt <= clock.millis()
        ) {
            if (pending != null) pendingTotpSetups.remove(uniqueId, pending)
            return AuthResult.TOTP_SETUP_REQUIRED
        }
        synchronized(pending) {
            if (pendingTotpSetups[uniqueId] !== pending) return AuthResult.TOTP_SETUP_REQUIRED
            if (code == null || !totp.verify(pending.setup.secret, code)) return failedTotp(attemptKey(uniqueId, session), session)
            val encryptedSecret = totp.encrypt(pending.setup.secret)
            totp.replaceTotpData(uniqueId, encryptedSecret, pending.setup.recoveryCodes)
            val record = withTotp(session.record, encryptedSecret)
            replaceSession(uniqueId, session, Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip))
            pendingTotpSetups.remove(uniqueId, pending)
            eventsBus.publish(TotpStateChangedEvent(uniqueId, record.realName(), true))
            return AuthResult.TOTP_ENABLED
        }
    }

    private fun isTotpLocked(uniqueId: UUID): Boolean {
        val state = failedTotpAttempts[uniqueId] ?: return false
        synchronized(state) {
            if (state.lockedUntil != 0L && state.lockedUntil <= clock.millis()) {
                failedTotpAttempts.remove(uniqueId, state)
                return false
            }
            return state.lockedUntil != 0L
        }
    }

    private fun failedTotp(attemptKey: UUID, session: Session): AuthResult {
        val state = failedTotpAttempts.computeIfAbsent(attemptKey) { AttemptState() }
        synchronized(state) {
            state.failures++
            if (state.failures >= features.totpMaxAttempts) {
                state.lockedUntil = clock.millis() + features.totpLockoutDuration.toMillis()
                if (features.banOnFailedLogin && session.ip != null) {
                    bans.ban(session.ip, features.banDuration)
                }
                return AuthResult.LOCKED_OUT
            }
            return AuthResult.TOTP_INVALID
        }
    }

    private fun verifyTotpOrRecoveryCode(uniqueId: UUID, encryptedSecret: String?, code: String?): Boolean {
        if (code == null) return false
        try {
            if (!encryptedSecret.isNullOrBlank() && totp.verify(totp.decrypt(encryptedSecret), code)) {
                return true
            }
        } catch (ignored: RuntimeException) {
            // A damaged encrypted secret must not make recovery codes unusable.
        }
        return totp.consumeRecoveryCode(uniqueId, code)
    }

    private fun isLocked(uniqueId: UUID): Boolean {
        val state = failedAttempts[uniqueId] ?: return false
        synchronized(state) {
            if (state.lockedUntil != 0L && state.lockedUntil <= clock.millis()) {
                failedAttempts.remove(uniqueId, state)
                return false
            }
            return state.lockedUntil != 0L
        }
    }

    private fun replaceSession(uniqueId: UUID, expected: Session, replacement: Session) {
        sessions.replace(uniqueId, expected, replacement)
    }

    private fun <T> async(supplier: Supplier<T>): CompletableFuture<T> {
        return CompletableFuture.supplyAsync(supplier, executor)
    }

    private fun guarded(
        operation: AuthOperation, uniqueId: UUID, operationBody: Supplier<AuthResult>
    ): CompletableFuture<AuthResult> {
        val session = sessions[uniqueId]
        val username = session?.record?.realName() ?: ""
        val ip = session?.ip
        return guarded(AuthOperationContext.user(operation, uniqueId, username, ip), operationBody)
    }

    private fun guarded(
        context: AuthOperationContext, operationBody: Supplier<AuthResult>
    ): CompletableFuture<AuthResult> {
        val preEvent = PreAuthOperationEvent(context)
        eventsBus.publish(preEvent)
        if (preEvent.cancelled()) {
            return completedOperation(context, AuthResult.OPERATION_DENIED)
        }
        return extensionRegistry.evaluate(context).thenCompose { decision ->
            if (decision.type == AuthPolicyDecision.Type.DENY) {
                return@thenCompose completedOperation(context, AuthResult.OPERATION_DENIED)
            }
            if (decision.type == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) {
                return@thenCompose completedOperation(context, AuthResult.ADDITIONAL_VERIFICATION_REQUIRED)
            }
            async(operationBody).thenApply { result -> finishOperation(context, result) }
        }.toCompletableFuture()
    }

    private fun completedOperation(context: AuthOperationContext, result: AuthResult): CompletableFuture<AuthResult> {
        finishOperation(context, result)
        return CompletableFuture.completedFuture(result)
    }

    private fun finishOperation(context: AuthOperationContext, result: AuthResult): AuthResult {
        eventsBus.publish(AuthOperationCompletedEvent(context, result))
        publishAuthenticationAttempt(context, result)
        return result
    }

    private fun <T> guardedValue(context: AuthOperationContext, operationBody: Supplier<T>): CompletableFuture<T> {
        val preEvent = PreAuthOperationEvent(context)
        eventsBus.publish(preEvent)
        if (preEvent.cancelled()) return CompletableFuture.failedFuture(
            AuthOperationRejectedException(
                AuthResult.OPERATION_DENIED, preEvent.cancellationReason()
            )
        )
        return extensionRegistry.evaluate(context).thenCompose { decision ->
            if (decision.type == AuthPolicyDecision.Type.DENY) {
                return@thenCompose CompletableFuture.failedFuture<T>(
                    AuthOperationRejectedException(
                        AuthResult.OPERATION_DENIED, decision.message
                    )
                )
            }
            if (decision.type == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) {
                return@thenCompose CompletableFuture.failedFuture<T>(
                    AuthOperationRejectedException(
                        AuthResult.ADDITIONAL_VERIFICATION_REQUIRED, decision.message
                    )
                )
            }
            async(operationBody)
        }.toCompletableFuture()
    }

    private fun publishAuthenticationAttempt(context: AuthOperationContext, result: AuthResult) {
        if (context.uniqueId != null && (context.operation == AuthOperation.LOGIN || context.operation == AuthOperation.TOTP_VERIFY)) {
            eventsBus.publish(
                AuthenticationAttemptEvent(
                    context.uniqueId, context.username ?: "",
                    context.operation, result
                )
            )
        }
    }

    private fun attachContinuation(uniqueId: UUID, continuation: Runnable) {
        extensionRegistry.pending(uniqueId).ifPresent { ticket -> verificationContinuations[ticket.id] = continuation }
    }

    private fun completeVerifiedAuthentication(
        uniqueId: UUID, expected: Session, context: AuthOperationContext,
        cause: UserAuthenticatedEvent.Cause
    ) {
        if (extensionRegistry.evaluate(context).toCompletableFuture().join().type != AuthPolicyDecision.Type.ALLOW) return
        val current = sessions[uniqueId]
        if (current == null || current.generation != expected.generation || current.status == AuthStatus.AUTHENTICATED) return
        val result = authenticate(uniqueId, current, cause)
        finishOperation(context, result)
    }

    private fun authenticate(uniqueId: UUID, session: Session, cause: UserAuthenticatedEvent.Cause): AuthResult {
        failedAttempts.remove(attemptKey(uniqueId, session))
        val identity = if (session.record!!.uniqueId == uniqueId) session.record else reassignIdentity(session.record, uniqueId)
        val now = clock.millis()
        repository.updateLastLogin(uniqueId, now)
        if (session.ip != null) repository.updateLastIp(uniqueId, session.ip)
        val record = AuthRecord(
            identity.uniqueId, identity.username, identity.realName,
            identity.passwordHash, identity.registeredAt, now,
            identity.premium, identity.registeredIp, session.ip, identity.totpSecret,
            identity.dialogPreference
        )
        replaceSession(uniqueId, session, Session(session.generation, record, AuthStatus.AUTHENTICATED, session.ip))
        eventsBus.publish(UserAuthenticatedEvent(uniqueId, record.realName, cause))
        return AuthResult.SUCCESS
    }

    private fun upgradePasswordHash(uniqueId: UUID, session: Session, password: String): Session {
        if (!PasswordHasher.needsRehash(session.record!!.passwordHash, settings)) {
            return session
        }
        val upgraded = PasswordHasher.hash(password, settings)
        repository.updatePassword(session.record.uniqueId, upgraded)
        val replacement = Session(
            session.generation,
            session.record.withPasswordHash(upgraded),
            session.status,
            session.ip
        )
        return if (sessions.replace(uniqueId, session, replacement)) replacement else session
    }

    private fun reassignIdentity(record: AuthRecord, uniqueId: UUID): AuthRecord {
        if (!repository.reassignUniqueId(record.uniqueId, uniqueId)) {
            throw IllegalStateException("Could not bind account to the current player UUID")
        }
        return AuthRecord(
            uniqueId, record.username, record.realName, record.passwordHash, record.registeredAt,
            record.lastLoginAt, record.premium, record.registeredIp, record.lastIp, record.totpSecret,
            record.dialogPreference
        )
    }

    private fun sessionIpOrEmpty(uniqueId: UUID): String {
        return sessions[uniqueId]?.ip ?: ""
    }

    private data class Session @JvmOverloads constructor(
        val generation: Long,
        val record: AuthRecord?,
        val status: AuthStatus,
        val ip: String? = null
    )

    private data class PendingTotpSetup(val generation: Long, val setup: TotpSetup, val expiresAt: Long)

    private data class LoginPreparation(val session: Session?, val result: AuthResult?)

    private data class TotpPreparation(val session: Session?, val result: AuthResult?)

    private class AttemptState {
        var failures: Int = 0
        var lockedUntil: Long = 0
    }

    companion object {
        /**
         * AuthService is used across proxy and server platforms, so it must stay lightweight by default.
         *
         * Override via JVM property `pnauth.workerThreads` when running large networks:
         * `-Dpnauth.workerThreads=8`.
         */
        private fun workerThreads(): Int {
            val configured = System.getProperty("pnauth.workerThreads")?.toIntOrNull()
            val defaultThreads = kotlin.math.max(2, Runtime.getRuntime().availableProcessors())
            return (configured ?: defaultThreads).coerceIn(1, 64)
        }

        private fun withTotp(record: AuthRecord, secret: String?): AuthRecord {
            return AuthRecord(
                record.uniqueId, record.username, record.realName, record.passwordHash, record.registeredAt,
                record.lastLoginAt, record.premium, record.registeredIp, record.lastIp, secret,
                record.dialogPreference
            )
        }

        private fun withLastIp(record: AuthRecord, lastIp: String?): AuthRecord {
            return AuthRecord(
                record.uniqueId, record.username, record.realName, record.passwordHash, record.registeredAt,
                record.lastLoginAt, record.premium, record.registeredIp, lastIp, record.totpSecret,
                record.dialogPreference
            )
        }

        private fun attemptKey(uniqueId: UUID, session: Session): UUID {
            return session.record?.uniqueId ?: uniqueId
        }

        private fun randomKey(): ByteArray {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            return key
        }

        private fun normalizeUsername(username: String?): String {
            return username?.trim()?.lowercase(Locale.ROOT) ?: ""
        }
    }
}
