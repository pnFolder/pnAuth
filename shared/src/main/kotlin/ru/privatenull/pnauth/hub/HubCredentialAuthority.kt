package ru.privatenull.pnauth.hub

import ru.privatenull.pnauth.security.CredentialAuthority
import ru.privatenull.pnauth.security.CredentialDecision
import ru.privatenull.pnauth.security.SecondFactorAuthority
import ru.privatenull.pnauth.api.TotpSetup
import ru.privatenull.pnauth.security.CentralAccountAuthority
import ru.privatenull.pnauth.api.AdmissionDecision
import java.util.UUID
import java.util.concurrent.CompletionStage

class HubCredentialAuthority(private val client: HubApiClient) : CredentialAuthority, SecondFactorAuthority, CentralAccountAuthority {
    override fun lookup(uniqueId: UUID, username: String): CompletionStage<CredentialDecision> =
        client.lookup(uniqueId, username).thenApply(::decision).exceptionally { unavailable() }

    override fun register(
        uniqueId: UUID, username: String, password: String, ip: String?
    ): CompletionStage<CredentialDecision> = client.register(uniqueId, username, password, ip)
        .thenApply(::decision).exceptionally { unavailable() }

    override fun verify(
        uniqueId: UUID, username: String, password: String, ip: String?
    ): CompletionStage<CredentialDecision> = client.verify(uniqueId, username, password, ip)
        .thenApply(::decision).exceptionally { unavailable() }

    override fun changePassword(
        uniqueId: UUID, currentPassword: String, newPassword: String
    ): CompletionStage<CredentialDecision> = client.changePassword(uniqueId, currentPassword, newPassword)
        .thenApply(::decision).exceptionally { unavailable() }

    override fun beginSetup(uniqueId: UUID, password: String, issuer: String): CompletionStage<TotpSetup> =
        client.beginTotp(uniqueId, password, issuer).thenApply { value ->
            value.setup() ?: throw IllegalStateException("Hub refused TOTP setup: ${value.status}")
        }

    override fun confirmSetup(uniqueId: UUID, code: String): CompletionStage<CredentialDecision> =
        client.confirmTotp(uniqueId, code).thenApply(::decision).exceptionally { unavailable() }

    override fun verify(uniqueId: UUID, code: String): CompletionStage<CredentialDecision> =
        client.verifyTotp(uniqueId, code).thenApply(::decision).exceptionally { unavailable() }

    override fun disable(uniqueId: UUID, password: String, code: String): CompletionStage<CredentialDecision> =
        client.disableTotp(uniqueId, password, code).thenApply(::decision).exceptionally { unavailable() }

    override fun unregister(uniqueId: UUID, password: String): CompletionStage<CredentialDecision> =
        client.unregister(uniqueId, password).thenApply(::decision).exceptionally { unavailable() }

    override fun adminUnregister(username: String): CompletionStage<CredentialDecision> =
        client.adminUnregister(username).thenApply(::decision).exceptionally { unavailable() }

    override fun adminChangePassword(username: String, password: String): CompletionStage<CredentialDecision> =
        client.adminChangePassword(username, password).thenApply(::decision).exceptionally { unavailable() }

    override fun forceRegister(username: String, password: String): CompletionStage<CredentialDecision> =
        client.forceRegister(username, password).thenApply(::decision).exceptionally { unavailable() }

    override fun togglePremium(username: String): CompletionStage<CredentialDecision> =
        client.togglePremium(username).thenApply(::decision).exceptionally { unavailable() }

    override fun isPremium(username: String): CompletionStage<Boolean> =
        client.premium(username).thenApply { it.premium }.exceptionally { false }

    override fun checkAdmission(
        username: String, ip: String, onlineAccounts: Int, maxOnline: Int, maxRegistered: Int, excluded: Boolean
    ): CompletionStage<AdmissionDecision> = client.admission(
        username, ip, onlineAccounts, maxOnline, maxRegistered, excluded
    ).thenApply { value ->
        val reason = runCatching { AdmissionDecision.Reason.valueOf(value.reason) }
            .getOrDefault(AdmissionDecision.Reason.POLICY_DENIED)
        AdmissionDecision(value.allowed, value.premium, reason)
    }.exceptionally { AdmissionDecision(false, false, AdmissionDecision.Reason.DATABASE_ERROR) }

    private fun decision(value: HubCredentialResult): CredentialDecision {
        val status = runCatching { CredentialDecision.Status.valueOf(value.status) }
            .getOrDefault(CredentialDecision.Status.UNAVAILABLE)
        return CredentialDecision(
            status, value.uniqueId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            value.username.orEmpty(), value.premium, value.totpEnabled
        )
    }

    private fun unavailable() = CredentialDecision(
        CredentialDecision.Status.UNAVAILABLE, null, "", false, false
    )
}
