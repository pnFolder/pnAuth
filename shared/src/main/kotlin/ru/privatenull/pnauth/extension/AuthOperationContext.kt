package ru.privatenull.pnauth.extension

import java.util.UUID

@JvmRecord
data class AuthOperationContext @JvmOverloads constructor(
    val operation: AuthOperation,
    val phase: AuthPhase = AuthPhase.BEFORE_EXECUTION,
    val uniqueId: UUID?,
    val username: String?,
    val ip: String?,
    val attributes: Map<String, String> = emptyMap()
) {
    constructor(
        operation: AuthOperation,
        uniqueId: UUID?,
        username: String?,
        ip: String?,
        attributes: Map<String, String>?
    ) : this(operation, AuthPhase.BEFORE_EXECUTION, uniqueId, username, ip, attributes ?: emptyMap())

    fun at(value: AuthPhase): AuthOperationContext {
        return AuthOperationContext(operation, value, uniqueId, username, ip, attributes)
    }

    companion object {
        @JvmStatic
        fun user(operation: AuthOperation, id: UUID?, username: String?, ip: String?): AuthOperationContext {
            return AuthOperationContext(operation, AuthPhase.BEFORE_EXECUTION, id, username, ip, emptyMap())
        }
    }
}
