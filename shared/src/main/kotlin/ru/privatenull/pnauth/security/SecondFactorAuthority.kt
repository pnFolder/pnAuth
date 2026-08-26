package ru.privatenull.pnauth.security

import ru.privatenull.pnauth.api.TotpSetup
import java.util.UUID
import java.util.concurrent.CompletionStage

interface SecondFactorAuthority {
    fun beginSetup(uniqueId: UUID, password: String, issuer: String): CompletionStage<TotpSetup>
    fun confirmSetup(uniqueId: UUID, code: String): CompletionStage<CredentialDecision>
    fun verify(uniqueId: UUID, code: String): CompletionStage<CredentialDecision>
    fun disable(uniqueId: UUID, password: String, code: String): CompletionStage<CredentialDecision>
}
