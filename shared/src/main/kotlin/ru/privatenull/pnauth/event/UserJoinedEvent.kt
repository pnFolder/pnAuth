package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.api.AuthStatus
import java.util.UUID

@JvmRecord
data class UserJoinedEvent(
    private val _uniqueId: UUID,
    private val _username: String,
    val ip: String,
    val status: AuthStatus
) : UserAuthEvent {
    override fun uniqueId(): UUID = _uniqueId
    override fun username(): String = _username
}
