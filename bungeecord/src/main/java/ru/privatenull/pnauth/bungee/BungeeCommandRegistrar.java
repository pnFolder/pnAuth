package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import ru.privatenull.pnauth.command.CommandService;
import ru.privatenull.pnauth.message.MessageFormat;

import java.util.ArrayList;
import java.util.List;

final class BungeeCommandRegistrar implements AutoCloseable {
    private final Plugin owner;
    private final PluginManager pluginManager;
    private final CommandService commandService;
    private final MessageFormat messageFormat;
    private final List<Command> registered = new ArrayList<>();

    BungeeCommandRegistrar(Plugin owner, PluginManager pluginManager, CommandService commandService,
                           MessageFormat messageFormat) {
        this.owner = owner;
        this.pluginManager = pluginManager;
        this.commandService = commandService;
        this.messageFormat = messageFormat;
    }

    void register() {
        for (var definition : commandService.definitions()) {
            AuthBungeeCommand command = new AuthBungeeCommand(definition, commandService, messageFormat);
            registered.add(command);
            pluginManager.registerCommand(owner, command);
        }
    }

    @Override
    public void close() {
        registered.forEach(pluginManager::unregisterCommand);
        registered.clear();
    }
}
