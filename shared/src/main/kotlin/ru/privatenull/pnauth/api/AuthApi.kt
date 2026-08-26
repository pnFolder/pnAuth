package ru.privatenull.pnauth.api

import ru.privatenull.pnauth.kernel.ExtensionKernel
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Public service exposed by both proxy adapters. */
interface AuthApi : AuthSessionApi, AuthTotpApi, AuthAdminApi, AuthAdmissionApi, AuthDialogApi,
    AuthEventApi, AuthExtensionApi, AuthDisplayApi, ExtensionKernel, AutoCloseable {
    fun user(uniqueId: UUID): Optional<AuthUser>

    fun togglePremium(uniqueId: UUID): CompletableFuture<AuthResult>

    fun unregister(uniqueId: UUID, password: String): CompletableFuture<AuthResult>

    override fun close()
}
