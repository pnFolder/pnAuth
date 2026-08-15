package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.kernel.event.DecisionEvent

interface CancellableAuthEvent : AuthEvent, DecisionEvent {
    fun cancellationReason(): String? = effectiveDecision().reason
    fun cancel(reason: String?) {
        setDecision(true, reason)
    }
}
