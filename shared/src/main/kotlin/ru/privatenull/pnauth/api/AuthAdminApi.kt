package ru.privatenull.pnauth.api

import java.util.concurrent.CompletableFuture

/** Administrative account operations. */
interface AuthAdminApi {
    fun unregister(username: String): CompletableFuture<AuthResult>
    fun adminChangePassword(username: String, newPassword: String): CompletableFuture<AuthResult>
    fun forceRegister(username: String, password: String): CompletableFuture<AuthResult>
    fun forceLogin(username: String): CompletableFuture<AuthResult>
    fun togglePremium(username: String): CompletableFuture<AuthResult>
}
