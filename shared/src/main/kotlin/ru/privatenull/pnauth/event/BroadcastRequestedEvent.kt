package ru.privatenull.pnauth.event

@JvmRecord
data class BroadcastRequestedEvent(val message: String) : AuthEvent
