package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.ProxyServer;
import ru.privatenull.pnauth.command.CommandService;
import ru.privatenull.pnauth.message.MessageFormat;

import java.util.ArrayList;
import java.util.List;

final class VelocityCommandRegistrar implements AutoCloseable {
    private final ProxyServer proxy;
    private final CommandService commandService;
    private final MessageFormat messageFormat;
    private final List<String> registered = new ArrayList<>();

    VelocityCommandRegistrar(ProxyServer proxy, CommandService commandService, MessageFormat messageFormat) {
        this.proxy = proxy;
        this.commandService = commandService;
        this.messageFormat = messageFormat;
    }

    void register() {
        CommandManager commandManager = proxy.getCommandManager();
        for (var definition : commandService.definitions()) {
            commandManager.register(
                    commandManager.metaBuilder(definition.name()).aliases(definition.aliases().toArray(String[]::new)).build(),
                    new AuthVelocityCommand(definition, commandService, messageFormat)
            );
            registered.add(definition.name());
        }
    }

    @Override
    public void close() {
        registered.forEach(proxy.getCommandManager()::unregister);
        registered.clear();
    }
}
