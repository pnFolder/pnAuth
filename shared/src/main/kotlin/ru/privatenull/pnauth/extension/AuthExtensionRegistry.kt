package ru.privatenull.pnauth.extension

import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.function.Consumer

interface AuthExtensionRegistry {
    fun register(id: String, priority: Int, hook: AuthPolicyHook): AuthExtensionRegistration
    fun evaluate(context: AuthOperationContext): CompletionStage<AuthPolicyDecision>
    fun pending(uniqueId: UUID): Optional<VerificationTicket>
    fun approve(ticketId: String): Boolean
    fun deny(ticketId: String): Boolean
    fun onTicket(listener: Consumer<VerificationTicket>): AuthExtensionRegistration
}
