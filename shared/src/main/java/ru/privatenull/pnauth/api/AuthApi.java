package ru.privatenull.pnauth.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Public service exposed by both proxy adapters. */
public interface AuthApi extends AutoCloseable {
    CompletableFuture<AuthStatus> onJoin(UUID uniqueId, String username);

    default CompletableFuture<AuthStatus> onJoin(UUID uniqueId, String username, String ip) {
        return onJoin(uniqueId, username);
    }

    void onQuit(UUID uniqueId);

    CompletableFuture<AuthResult> register(UUID uniqueId, String username, String password, String confirmation);

    CompletableFuture<AuthResult> login(UUID uniqueId, String password);

    CompletableFuture<AuthResult> logout(UUID uniqueId);

    CompletableFuture<AuthResult> changePassword(UUID uniqueId, String oldPassword, String newPassword);

    CompletableFuture<TotpSetup> beginTotpSetup(UUID uniqueId, String password, String issuer);

    CompletableFuture<AuthResult> confirmTotpSetup(UUID uniqueId, String code);

    CompletableFuture<AuthResult> verifyTotp(UUID uniqueId, String code);

    CompletableFuture<AuthResult> disableTotp(UUID uniqueId, String password, String code);

    AuthStatus status(UUID uniqueId);

    Optional<AuthUser> user(UUID uniqueId);

    boolean isAuthenticated(UUID uniqueId);

    CompletableFuture<Boolean> isPremium(String username);

    CompletableFuture<AuthResult> togglePremium(UUID uniqueId);

    CompletableFuture<AuthResult> unregister(String username);

    CompletableFuture<AuthResult> unregister(UUID uniqueId, String password);

    CompletableFuture<AuthResult> adminChangePassword(String username, String newPassword);

    CompletableFuture<AuthResult> forceRegister(String username, String password);

    CompletableFuture<AuthResult> forceLogin(String username);

    CompletableFuture<AuthResult> togglePremium(String username);

    CompletableFuture<AdmissionDecision> checkAdmission(String username, String ip, int onlineAccountsFromIp);

    DialogPreference dialogPreference(UUID uniqueId);

    CompletableFuture<AuthResult> setDialogPreference(UUID uniqueId, DialogPreference preference);

    boolean shouldUseDialog(UUID uniqueId, int clientProtocol, boolean platformSupportsDialogs);

    default boolean shouldUseCommandFallback(UUID uniqueId, int clientProtocol, boolean platformSupportsDialogs) {
        return !shouldUseDialog(uniqueId, clientProtocol, platformSupportsDialogs);
    }

    @Override
    void close();
}
