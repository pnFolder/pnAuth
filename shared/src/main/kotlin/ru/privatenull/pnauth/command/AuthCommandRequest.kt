package ru.privatenull.pnauth.command

import java.util.UUID
import java.util.function.Predicate

@JvmRecord
data class AuthCommandRequest(
    val uniqueId: UUID?,
    val username: String?,
    val command: String = "",
    val arguments: List<String> = emptyList(),
    val permissionChecker: Predicate<String> = Predicate { false }
) {
    constructor(
        uniqueId: UUID?,
        username: String?,
        command: String?,
        arguments: List<String>?
    ) : this(
        uniqueId,
        username,
        command ?: "",
        arguments?.toList() ?: emptyList(),
        Predicate { false }
    )

    fun isPlayer(): Boolean = uniqueId != null

    fun hasPermission(permission: String): Boolean = permissionChecker.test(permission)
}
