package ru.privatenull.pnauth.flow

import java.util.UUID

@JvmRecord
data class PlayerConnection(val uniqueId: UUID, val username: String, val ip: String)
