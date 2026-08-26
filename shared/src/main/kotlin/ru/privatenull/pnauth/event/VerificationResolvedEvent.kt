package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.extension.VerificationTicket

@JvmRecord
data class VerificationResolvedEvent(val ticket: VerificationTicket) : AuthEvent
