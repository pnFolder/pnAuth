package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.api.AuthResult
import ru.privatenull.pnauth.extension.AuthOperationContext

@JvmRecord
data class AuthOperationCompletedEvent(
    val context: AuthOperationContext,
    val result: AuthResult
) : AuthEvent
