package ru.privatenull.pnauth.api

import java.time.Instant
import java.util.UUID

@JvmRecord
data class AuthUser @JvmOverloads constructor(
    val uniqueId: UUID,
    val username: String,
    val registeredAt: Instant?,
    val lastLoginAt: Instant?,
    val premium: Boolean = false,
    val totpEnabled: Boolean = false,
    val lastIp: String? = null,
    val dialogPreference: DialogPreference = DialogPreference.AUTO
)

