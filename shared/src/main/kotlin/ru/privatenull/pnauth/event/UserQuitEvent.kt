package ru.privatenull.pnauth.event

import java.util.UUID

@JvmRecord
data class UserQuitEvent(
    private val _uniqueId: UUID,
    private val _username: String
) : UserAuthEvent {
    override fun uniqueId(): UUID = _uniqueId
    override fun username(): String = _username
}
