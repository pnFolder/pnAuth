package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.api.AdmissionDecision

@JvmRecord
data class AdmissionEvaluatedEvent(
    val username: String,
    val ip: String,
    val decision: AdmissionDecision
) : AuthEvent
