package ru.privatenull.pnauth.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Optional second-factor operations. */
public interface AuthTotpApi {
    CompletableFuture<TotpSetup> beginTotpSetup(UUID uniqueId, String password, String issuer);
    CompletableFuture<AuthResult> confirmTotpSetup(UUID uniqueId, String code);
    CompletableFuture<AuthResult> verifyTotp(UUID uniqueId, String code);
    CompletableFuture<AuthResult> disableTotp(UUID uniqueId, String password, String code);
}
