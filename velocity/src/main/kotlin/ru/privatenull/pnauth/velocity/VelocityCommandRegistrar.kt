package ru.privatenull.pnauth.velocity

import com.velocitypowered.api.proxy.ProxyServer
import ru.privatenull.pnauth.command.CommandService
import ru.privatenull.pnauth.message.MessageFormat

internal class VelocityCommandRegistrar(
    private val proxy: ProxyServer,
    private val commandService: CommandService,
    private val messageFormat: MessageFormat,
    private val reloadConfiguration: () -> String
) : AutoCloseable {

    private val registered = mutableListOf<String>()

    fun register() {
        val commandManager = proxy.commandManager
        for (definition in commandService.definitions()) {
            val meta = commandManager.metaBuilder(definition.name)
                .aliases(*definition.aliases.toTypedArray())
                .build()
            commandManager.register(
                meta,
                AuthVelocityCommand(definition, commandService, messageFormat, reloadConfiguration)
            )
            registered.add(definition.name)
        }
    }

    override fun close() {
        registered.forEach { command -> proxy.commandManager.unregister(command) }
        registered.clear()
    }
}
