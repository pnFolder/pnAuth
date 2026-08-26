package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.PluginManager
import ru.privatenull.pnauth.command.CommandService
import ru.privatenull.pnauth.message.AuthMessages

internal class BungeeCommandRegistrar(
    private val owner: Plugin,
    private val pluginManager: PluginManager,
    private val commandService: CommandService,
    private val messages: AuthMessages,
    private val reloadConfiguration: () -> String
) : AutoCloseable {
    private val registered: MutableList<Command> = ArrayList()

    fun register() {
        for (definition in commandService.definitions()) {
            val command = AuthBungeeCommand(definition, commandService, messages, reloadConfiguration)
            registered.add(command)
            pluginManager.registerCommand(owner, command)
        }
    }

    override fun close() {
        registered.forEach { pluginManager.unregisterCommand(it) }
        registered.clear()
    }
}
