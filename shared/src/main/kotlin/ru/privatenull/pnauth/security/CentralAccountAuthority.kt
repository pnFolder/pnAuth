package ru.privatenull.pnauth.security

import ru.privatenull.pnauth.api.AdmissionDecision
import java.util.UUID
import java.util.concurrent.CompletionStage

interface CentralAccountAuthority {
    fun unregister(uniqueId: UUID, password: String): CompletionStage<CredentialDecision>
    fun adminUnregister(username: String): CompletionStage<CredentialDecision>
    fun adminChangePassword(username: String, password: String): CompletionStage<CredentialDecision>
    fun forceRegister(username: String, password: String): CompletionStage<CredentialDecision>
    fun togglePremium(username: String): CompletionStage<CredentialDecision>
    fun isPremium(username: String): CompletionStage<Boolean>
    fun checkAdmission(
        username: String, ip: String, onlineAccounts: Int, maxOnline: Int, maxRegistered: Int, excluded: Boolean
    ): CompletionStage<AdmissionDecision>
}
