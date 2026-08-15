package ru.privatenull.pnauth.paper

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import ru.privatenull.pnauth.command.CommandContext
import ru.privatenull.pnauth.command.CommandService

/** Thin Paper/Folia command adapter; command behavior lives in the shared service. */
internal class PaperAuthCommand(
    private val commands: CommandService
) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        arguments: Array<out String>
    ): Boolean {
        val context = CommandContext(
            PaperCommandSource(sender),
            command.name,
            arguments.toList()
        )
        commands.execute(context).thenAccept { messages ->
            messages.forEach { sender.sendMessage(it) }
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
