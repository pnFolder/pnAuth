package ru.privatenull.pnauth.limbo

import java.util.UUID

@JvmRecord
data class LimboDialogEvent(
    val playerId: UUID,
    val actionId: String,
    val dataJson: String
)
