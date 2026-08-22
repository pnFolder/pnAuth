package ru.privatenull.pnauth.paper

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.command.TabCompleter
import ru.privatenull.pnauth.command.CommandContext
import ru.privatenull.pnauth.command.CommandService
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageComponents

/** Thin Paper/Folia command adapter; command behavior lives in the shared service. */
internal class PaperAuthCommand(
    private val plugin: Plugin,
    private val commands: CommandService,
    private val messages: AuthMessages,
    private val reloadConfiguration: () -> String
) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        arguments: Array<out String>
    ): Boolean {
        if (command.name.equals("auth", ignoreCase = true) && arguments.size == 1 &&
            arguments[0].equals("reload", ignoreCase = true)) {
            val result = if (sender !is Player || sender.hasPermission("pnauth.admin.reload")) {
                reloadConfiguration()
            } else messages.text("no-permission")
            sender.sendMessage(MessageComponents.deserialize(result, messages.format))
            return true
        }
        val context = CommandContext(
            PaperCommandSource(sender),
            command.name,
            arguments.toList()
        )
        commands.execute(context).thenAccept { messages ->
            val deliver = Runnable {
                messages.forEach { sender.sendMessage(MessageComponents.deserialize(it, this.messages.format)) }
            }
            if (sender is Player) sender.scheduler.run(plugin, { deliver.run() }, null)
            else Bukkit.getGlobalRegionScheduler().execute(plugin, deliver)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        arguments: Array<out String>
    ): List<String>? {
        return commands.suggest(
            CommandContext(
                PaperCommandSource(sender),
                command.name,
                arguments.toList()
            )
        )
    }
}
