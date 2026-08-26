package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.extension.VerificationTicket

@JvmRecord
data class VerificationRequiredEvent(val ticket: VerificationTicket) : AuthEvent
