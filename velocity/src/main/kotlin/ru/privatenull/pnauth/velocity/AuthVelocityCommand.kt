package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.command.SimpleCommand
import ru.privatenull.pnauth.command.CommandContext
import ru.privatenull.pnauth.command.CommandService
import ru.privatenull.pnauth.command.CommandSpec
import ru.privatenull.pnauth.message.MessageFormat

class AuthVelocityCommand(
    definition: CommandSpec,
    private val handler: CommandService,
    private val messageFormat: MessageFormat
) : SimpleCommand {

    private val root: String = definition.name

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        handler.execute(
            CommandContext(
                VelocityCommandSource(source),
                root,
                invocation.arguments().toList()
            )
        ).thenAccept { messages ->
            messages.forEach { message ->
                source.sendMessage(VelocityMessages.component(message, messageFormat))
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
