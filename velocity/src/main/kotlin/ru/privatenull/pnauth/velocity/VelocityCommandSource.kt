package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import ru.privatenull.pnauth.command.CommandSource as PnCommandSource
import java.util.UUID

internal class VelocityCommandSource(
    private val source: CommandSource
) : PnCommandSource {

    override fun uniqueId(): UUID? {
        return (source as? Player)?.uniqueId
    }

    override fun username(): String? {
        return (source as? Player)?.username
    }

    override fun isPlayer(): Boolean {
        return source is Player
    }

    override fun hasPermission(permission: String): Boolean {
        return source.hasPermission(permission)
    }

    fun source(): CommandSource = source
}
