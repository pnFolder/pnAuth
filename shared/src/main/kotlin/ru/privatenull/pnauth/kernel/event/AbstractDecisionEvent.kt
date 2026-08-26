package ru.privatenull.pnauth.kernel.event

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

abstract class AbstractDecisionEvent : DecisionEvent {
    private val history = CopyOnWriteArrayList<EventDecision>()
    @Volatile
    private var effective = EventDecision(false, "kernel:default", EventPriority.LOWEST, "", Instant.now())

    override fun cancelled(): Boolean = effective.cancelled

    override fun setCancelled(cancelled: Boolean) {
        setDecision(cancelled, "")
    }

    @Synchronized
    override fun setDecision(cancelled: Boolean, reason: String?) {
        val actor = EventDispatchScope.current() ?: ListenerOptions.normal("external:direct")
        check(actor.mode != ListenerMode.MONITOR) { "MONITOR listeners are read-only" }
        val proposed = EventDecision(
            cancelled, actor.ownerId, actor.priority,
            reason ?: "", Instant.now()
        )
        history.add(proposed)
        if (actor.priority.value >= effective.priority.value) {
            effective = proposed
        }
    }

    override fun effectiveDecision(): EventDecision = effective
    override fun decisionHistory(): List<EventDecision> = history.toList()
}
