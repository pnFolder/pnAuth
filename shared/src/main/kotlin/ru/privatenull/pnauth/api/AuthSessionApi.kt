package ru.privatenull.pnauth.api

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Authentication lifecycle operations exposed to platform adapters. */
interface AuthSessionApi {
    fun onJoin(uniqueId: UUID, username: String): CompletableFuture<AuthStatus>

    fun onJoin(uniqueId: UUID, username: String, ip: String?): CompletableFuture<AuthStatus> {
        return onJoin(uniqueId, username)
    }

    fun onQuit(uniqueId: UUID)

    fun register(uniqueId: UUID, username: String, password: String, confirmation: String): CompletableFuture<AuthResult>

    fun login(uniqueId: UUID, password: String): CompletableFuture<AuthResult>

    fun logout(uniqueId: UUID): CompletableFuture<AuthResult>

    fun changePassword(uniqueId: UUID, oldPassword: String, newPassword: String): CompletableFuture<AuthResult>

    fun status(uniqueId: UUID): AuthStatus

    fun isAuthenticated(uniqueId: UUID): Boolean
}
