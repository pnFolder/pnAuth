package ru.privatenull.pnauth.api;

import java.util.concurrent.CompletableFuture;

/** Administrative account operations. */
public interface AuthAdminApi {
    CompletableFuture<AuthResult> unregister(String username);
    CompletableFuture<AuthResult> adminChangePassword(String username, String newPassword);
    CompletableFuture<AuthResult> forceRegister(String username, String password);
    CompletableFuture<AuthResult> forceLogin(String username);
    CompletableFuture<AuthResult> togglePremium(String username);
}
