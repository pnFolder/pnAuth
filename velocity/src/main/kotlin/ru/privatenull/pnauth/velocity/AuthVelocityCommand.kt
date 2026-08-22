package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import ru.privatenull.pnauth.command.CommandContext
import ru.privatenull.pnauth.command.CommandService
import ru.privatenull.pnauth.command.CommandSpec
import ru.privatenull.pnauth.message.AuthMessages

class AuthVelocityCommand(
    definition: CommandSpec,
    private val handler: CommandService,
    private val messages: AuthMessages,
    private val reloadConfiguration: () -> String
) : SimpleCommand {

    private val root: String = definition.name

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        if (root == "auth" && invocation.arguments().size == 1 &&
            invocation.arguments()[0].equals("reload", ignoreCase = true)) {
            val result = if (source !is Player || source.hasPermission("pnauth.admin.reload")) {
                reloadConfiguration()
            } else {
                messages.text("no-permission")
            }
            source.sendMessage(VelocityMessages.component(result, messages.format))
            return
        }
        handler.execute(
            CommandContext(
                VelocityCommandSource(source),
                root,
                invocation.arguments().toList()
            )
        ).thenAccept { messages ->
            messages.forEach { message ->
                source.sendMessage(VelocityMessages.component(message, this.messages.format))
            }
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        return handler.suggest(
            CommandContext(
                VelocityCommandSource(invocation.source()),
                root,
                invocation.arguments().toList()
            )
        )
    }
}
