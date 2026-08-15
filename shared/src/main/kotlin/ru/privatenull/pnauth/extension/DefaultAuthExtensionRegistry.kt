package ru.privatenull.pnauth.extension

import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

class DefaultAuthExtensionRegistry internal constructor(
    private val clock: Clock
) : AuthExtensionRegistry {

    private val hooks = CopyOnWriteArrayList<HookEntry>()
    private val tickets = ConcurrentHashMap<String, TicketState>()
    private val ticketsByOperation = ConcurrentHashMap<OperationKey, String>()
    private val ticketListeners = CopyOnWriteArrayList<Consumer<VerificationTicket>>()

    constructor() : this(Clock.systemUTC())

    override fun register(id: String, priority: Int, hook: AuthPolicyHook): AuthExtensionRegistration {
        require(id.isNotBlank()) { "extension id is required" }
        val entry = HookEntry(id, priority, hook)
        hooks.add(entry)
        hooks.sortWith(Comparator.comparingInt<HookEntry> { it.priority }.reversed())
        return AuthExtensionRegistration { hooks.remove(entry) }
    }

    override fun evaluate(context: AuthOperationContext): CompletionStage<AuthPolicyDecision> {
        val key = OperationKey(context.uniqueId, context.username, context.operation, context.phase)
        val existingId = ticketsByOperation[key]
        if (existingId != null) {
            val existing = tickets[existingId]
            if (existing != null && existing.expiresAt > clock.millis()) {
                if (existing.status == VerificationTicket.Status.APPROVED && tickets.remove(existingId, existing)) {
                    ticketsByOperation.remove(key, existingId)
                    return CompletableFuture.completedFuture(AuthPolicyDecision.allow())
                }
                if (existing.status == VerificationTicket.Status.PENDING) {
                    return CompletableFuture.completedFuture(existing.decision)
                }
            }
            tickets.remove(existingId)
            ticketsByOperation.remove(key, existingId)
        }
        return evaluateHook(context, 0).thenApply { decision ->
            if (decision.type == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) createTicket(key, context, decision)
            decision
        }
    }

    private fun evaluateHook(context: AuthOperationContext, index: Int): CompletionStage<AuthPolicyDecision> {
        if (index >= hooks.size) return CompletableFuture.completedFuture(AuthPolicyDecision.allow())
        val result: CompletionStage<AuthPolicyDecision> = try {
            hooks[index].hook.before(context)
        } catch (error: Throwable) {
            CompletableFuture.completedFuture(AuthPolicyDecision.deny("Extension failed"))
        }
        return result.handle { decision, error ->
            if (error == null && decision != null) decision else AuthPolicyDecision.deny("Extension failed")
        }.thenCompose { decision ->
            if (decision.type == AuthPolicyDecision.Type.ALLOW) evaluateHook(context, index + 1)
            else CompletableFuture.completedFuture(decision)
        }
    }

    private fun createTicket(key: OperationKey, context: AuthOperationContext, decision: AuthPolicyDecision) {
        val id = UUID.randomUUID().toString()
        val state = TicketState(
            id, key, context, decision,
            clock.millis() + decision.lifetime.toMillis(), VerificationTicket.Status.PENDING
        )
        tickets[id] = state
        val previous = ticketsByOperation.put(key, id)
        if (previous != null) tickets.remove(previous)
        notifyTicket(state)
    }

    override fun pending(uniqueId: UUID): Optional<VerificationTicket> {
        val now = clock.millis()
        return tickets.values.stream()
            .filter { ticket -> ticket.context.uniqueId == uniqueId }
            .filter { ticket -> ticket.expiresAt > now && ticket.status == VerificationTicket.Status.PENDING }
            .map { view(it) }
            .findFirst()
    }

    override fun approve(ticketId: String): Boolean = update(ticketId, VerificationTicket.Status.APPROVED)
    override fun deny(ticketId: String): Boolean = update(ticketId, VerificationTicket.Status.DENIED)

    private fun update(id: String, status: VerificationTicket.Status): Boolean {
        val state = tickets[id] ?: return false
        if (state.expiresAt <= clock.millis() || state.status != VerificationTicket.Status.PENDING) return false
        val updated = state.withStatus(status)
        val changed = tickets.replace(id, state, updated)
        if (changed) notifyTicket(updated)
        return changed
    }

    override fun onTicket(listener: Consumer<VerificationTicket>): AuthExtensionRegistration {
        ticketListeners.add(listener)
        return AuthExtensionRegistration { ticketListeners.remove(listener) }
    }

    private fun notifyTicket(state: TicketState) {
        val ticket = view(state)
        ticketListeners.forEach { listener ->
            try {
                listener.accept(ticket)
            } catch (ignored: RuntimeException) {
            }
        }
    }

    private fun view(state: TicketState): VerificationTicket {
        return VerificationTicket(
            state.id, state.decision.provider, state.context.uniqueId,
            state.context.username, state.context.operation, state.decision.message,
            Instant.ofEpochMilli(state.expiresAt), state.status
        )
    }

    private data class HookEntry(val id: String, val priority: Int, val hook: AuthPolicyHook)
    private data class OperationKey(
        val uniqueId: UUID?,
        val username: String?,
        val operation: AuthOperation,
        val phase: AuthPhase
    )

    private data class TicketState(
        val id: String,
        val key: OperationKey,
        val context: AuthOperationContext,
        val decision: AuthPolicyDecision,
        val expiresAt: Long,
        val status: VerificationTicket.Status
    ) {
        fun withStatus(value: VerificationTicket.Status): TicketState {
            return TicketState(id, key, context, decision, expiresAt, value)
        }
    }
}
