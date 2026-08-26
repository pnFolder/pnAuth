package ru.privatenull.pnauth.paper

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.privatenull.pnauth.command.CommandSource
import java.util.UUID

/** Adapts Bukkit command senders to the shared command model. */
internal class PaperCommandSource(private val sender: CommandSender) : CommandSource {
    override fun uniqueId(): UUID? = if (sender is Player) sender.uniqueId else null
    override fun username(): String? = if (sender is Player) sender.name else null
    override fun isPlayer(): Boolean = sender is Player
    override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)
}
