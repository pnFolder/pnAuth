package ru.privatenull.pnauth.kernel.event

interface DecisionEvent : ExtensionEvent {
    fun cancelled(): Boolean
    fun setCancelled(cancelled: Boolean)
    fun cancel() { setCancelled(true) }
    fun allow() { setCancelled(false) }
    fun setDecision(cancelled: Boolean, reason: String?)
    fun effectiveDecision(): EventDecision
    fun decisionHistory(): List<EventDecision>
}
