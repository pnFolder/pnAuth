package ru.privatenull.pnauth.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Authentication lifecycle operations exposed to platform adapters. */
public interface AuthSessionApi {
    CompletableFuture<AuthStatus> onJoin(UUID uniqueId, String username);

    default CompletableFuture<AuthStatus> onJoin(UUID uniqueId, String username, String ip) {
        return onJoin(uniqueId, username);
    }

    void onQuit(UUID uniqueId);

    CompletableFuture<AuthResult> register(UUID uniqueId, String username, String password, String confirmation);

    CompletableFuture<AuthResult> login(UUID uniqueId, String password);

    CompletableFuture<AuthResult> logout(UUID uniqueId);

    CompletableFuture<AuthResult> changePassword(UUID uniqueId, String oldPassword, String newPassword);

    AuthStatus status(UUID uniqueId);

    boolean isAuthenticated(UUID uniqueId);
}
