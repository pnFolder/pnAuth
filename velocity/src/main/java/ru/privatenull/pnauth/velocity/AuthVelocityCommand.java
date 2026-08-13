package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import ru.privatenull.pnauth.command.CommandContext;
import ru.privatenull.pnauth.command.CommandService;
import ru.privatenull.pnauth.command.CommandSpec;
import ru.privatenull.pnauth.message.MessageFormat;

import java.util.Arrays;
import java.util.List;

public final class AuthVelocityCommand implements SimpleCommand {
    private final String root;
    private final CommandService handler;
    private final MessageFormat messageFormat;

    public AuthVelocityCommand(CommandSpec definition, CommandService handler, MessageFormat messageFormat) {
        this.root = definition.name();
        this.handler = handler;
        this.messageFormat = messageFormat;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        handler.execute(new CommandContext(
                        new VelocityCommandSource(source), root, Arrays.asList(invocation.arguments())))
                .thenAccept(messages -> messages.forEach(message -> source.sendMessage(
                        VelocityMessages.component(message, messageFormat))));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return handler.suggest(new CommandContext(
                new VelocityCommandSource(invocation.source()), root, Arrays.asList(invocation.arguments())));
    }
}
