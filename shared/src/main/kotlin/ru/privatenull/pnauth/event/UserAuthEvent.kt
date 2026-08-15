package ru.privatenull.pnauth.event

import java.util.UUID

interface UserAuthEvent : AuthEvent {
    fun uniqueId(): UUID
    fun username(): String
}
