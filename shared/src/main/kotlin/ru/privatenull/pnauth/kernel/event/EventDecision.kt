package ru.privatenull.pnauth.kernel.event

import java.time.Instant

@JvmRecord
data class EventDecision(
    val cancelled: Boolean,
    val ownerId: String,
    val priority: EventPriority,
    val reason: String,
    val at: Instant
)
