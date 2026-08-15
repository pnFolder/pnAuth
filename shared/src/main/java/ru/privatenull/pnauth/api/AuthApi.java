package ru.privatenull.pnauth.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import ru.privatenull.pnauth.kernel.ExtensionKernel;

/** Public service exposed by both proxy adapters. */
public interface AuthApi extends AuthSessionApi, AuthTotpApi, AuthAdminApi, AuthAdmissionApi, AuthDialogApi,
        AuthEventApi, AuthExtensionApi, AuthDisplayApi, ExtensionKernel, AutoCloseable {
    Optional<AuthUser> user(UUID uniqueId);

    CompletableFuture<AuthResult> togglePremium(UUID uniqueId);

    CompletableFuture<AuthResult> unregister(UUID uniqueId, String password);

    @Override
    void close();
}
