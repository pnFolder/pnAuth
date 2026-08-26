package ru.privatenull.pnauth.event

import java.util.UUID

@JvmRecord
data class UserUnregisteredEvent(
    private val _uniqueId: UUID,
    private val _username: String,
    val administrative: Boolean
) : UserAuthEvent {
    override fun uniqueId(): UUID = _uniqueId
    override fun username(): String = _username
}
