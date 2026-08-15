package ru.privatenull.pnauth.extension

import java.time.Instant
import java.util.UUID

@JvmRecord
data class VerificationTicket(
    val id: String,
    val provider: String,
    val uniqueId: UUID?,
    val username: String?,
    val operation: AuthOperation,
    val message: String,
    val expiresAt: Instant,
    val status: Status
) {
    enum class Status { PENDING, APPROVED, DENIED, EXPIRED }
}
