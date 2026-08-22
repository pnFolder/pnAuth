package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.Command
import ru.privatenull.pnauth.command.CommandContext
import ru.privatenull.pnauth.command.CommandService
import ru.privatenull.pnauth.command.CommandSpec
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageFormat

class AuthBungeeCommand(
    definition: CommandSpec,
    private val handler: CommandService,
    private val messages: AuthMessages,
    private val reloadConfiguration: () -> String
) : Command(definition.name, null, *definition.aliases.toTypedArray()) {

    private val root: String = definition.name

    override fun execute(sender: CommandSender, args: Array<out String>) {
        if (root == "auth" && args.size == 1 && args[0].equals("reload", ignoreCase = true)) {
            if (sender is ProxiedPlayer && !sender.hasPermission("pnauth.admin.reload")) {
                send(sender, messages.text("no-permission"), messages.format)
                return
            }
            send(sender, reloadConfiguration(), messages.format)
            return
        }
        val source = BungeeCommandSource(sender)
        handler.execute(CommandContext(source, root, args.toList()))
            .thenAccept { messages ->
                messages.forEach { message -> send(sender, message, this.messages.format) }
            }
    }

    companion object {
        private fun send(sender: CommandSender, message: String, format: MessageFormat) {
            sender.sendMessage(*BungeeMessages.components(message, format))
        }
    }
}
