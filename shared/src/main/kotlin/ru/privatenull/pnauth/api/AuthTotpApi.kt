package ru.privatenull.pnauth.api

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Optional second-factor operations. */
interface AuthTotpApi {
    fun beginTotpSetup(uniqueId: UUID, password: String, issuer: String): CompletableFuture<TotpSetup>
    fun confirmTotpSetup(uniqueId: UUID, code: String): CompletableFuture<AuthResult>
    fun verifyTotp(uniqueId: UUID, code: String): CompletableFuture<AuthResult>
    fun disableTotp(uniqueId: UUID, password: String, code: String): CompletableFuture<AuthResult>
}
