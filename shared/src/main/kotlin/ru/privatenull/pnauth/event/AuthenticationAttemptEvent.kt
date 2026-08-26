package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.api.AuthResult
import ru.privatenull.pnauth.extension.AuthOperation
import java.util.UUID

@JvmRecord
data class AuthenticationAttemptEvent(
    private val _uniqueId: UUID,
    private val _username: String,
    val operation: AuthOperation,
    val result: AuthResult
) : UserAuthEvent {
    override fun uniqueId(): UUID = _uniqueId
    override fun username(): String = _username
}
