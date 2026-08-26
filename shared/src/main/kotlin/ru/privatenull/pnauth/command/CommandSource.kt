package ru.privatenull.pnauth.command

import java.util.UUID

interface CommandSource {
    fun uniqueId(): UUID?

    fun username(): String?

    fun isPlayer(): Boolean

    fun hasPermission(permission: String): Boolean
}
