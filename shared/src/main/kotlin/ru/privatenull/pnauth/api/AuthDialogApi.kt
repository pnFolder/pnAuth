package ru.privatenull.pnauth.api

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Client UI preference and capability negotiation. */
interface AuthDialogApi {
    fun dialogPreference(uniqueId: UUID): DialogPreference
    fun setDialogPreference(uniqueId: UUID, preference: DialogPreference): CompletableFuture<AuthResult>
    fun shouldUseDialog(uniqueId: UUID, clientProtocol: Int, platformSupportsDialogs: Boolean): Boolean

    fun shouldUseCommandFallback(uniqueId: UUID, clientProtocol: Int, platformSupportsDialogs: Boolean): Boolean {
        return !shouldUseDialog(uniqueId, clientProtocol, platformSupportsDialogs)
    }
}
