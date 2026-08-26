package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.connection.ProxiedPlayer
import ru.privatenull.pnauth.command.CommandSource
import java.util.UUID

internal class BungeeCommandSource(private val sender: CommandSender) : CommandSource {
    override fun uniqueId(): UUID? = if (sender is ProxiedPlayer) sender.uniqueId else null
    override fun username(): String? = if (sender is ProxiedPlayer) sender.name else null
    override fun isPlayer(): Boolean = sender is ProxiedPlayer
    override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)
    fun sender(): CommandSender = sender
}
