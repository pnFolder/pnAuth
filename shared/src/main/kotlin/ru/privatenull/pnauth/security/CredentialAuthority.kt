package ru.privatenull.pnauth.security

import java.util.UUID
import java.util.concurrent.CompletionStage

interface CredentialAuthority {
    fun lookup(uniqueId: UUID, username: String): CompletionStage<CredentialDecision>
    fun register(uniqueId: UUID, username: String, password: String, ip: String?): CompletionStage<CredentialDecision>
    fun verify(uniqueId: UUID, username: String, password: String, ip: String?): CompletionStage<CredentialDecision>
    fun changePassword(uniqueId: UUID, currentPassword: String, newPassword: String): CompletionStage<CredentialDecision>
}

data class CredentialDecision(
    val status: Status,
    val uniqueId: UUID?,
    val username: String,
    val premium: Boolean,
    val totpEnabled: Boolean
) {
    enum class Status {
        SUCCESS, REGISTERED, NOT_REGISTERED, ALREADY_REGISTERED, INVALID_CREDENTIALS,
        INVALID_NEW_PASSWORD, LOCKED_OUT, TOTP_REQUIRED, INVALID_REQUEST, UNAVAILABLE,
        TOTP_INVALID, TOTP_NOT_ENABLED, TOTP_ALREADY_ENABLED, TOTP_SETUP_REQUIRED, PLAYER_NOT_FOUND
    }
}
