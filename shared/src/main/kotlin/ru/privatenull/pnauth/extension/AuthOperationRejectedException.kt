package ru.privatenull.pnauth.extension

import ru.privatenull.pnauth.api.AuthResult

class AuthOperationRejectedException(
    val result: AuthResult,
    message: String?
) : RuntimeException(message) {
    fun result(): AuthResult = result
}
