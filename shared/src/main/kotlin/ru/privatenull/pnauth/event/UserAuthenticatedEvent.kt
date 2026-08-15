package ru.privatenull.pnauth.event

import java.util.UUID

@JvmRecord
data class UserAuthenticatedEvent(
    private val _uniqueId: UUID,
    private val _username: String,
    val cause: Cause
) : UserAuthEvent {
    override fun uniqueId(): UUID = _uniqueId
    override fun username(): String = _username

    enum class Cause { REGISTER, PASSWORD, TOTP, PREMIUM, SESSION, ADMIN }
}
