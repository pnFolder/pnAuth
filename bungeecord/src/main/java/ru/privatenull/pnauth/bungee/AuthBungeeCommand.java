package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import ru.privatenull.pnauth.command.CommandContext;
import ru.privatenull.pnauth.command.CommandService;
import ru.privatenull.pnauth.command.CommandSpec;
import ru.privatenull.pnauth.message.MessageFormat;

import java.util.Arrays;

public final class AuthBungeeCommand extends Command {
    private final String root;
    private final CommandService handler;
    private final MessageFormat messageFormat;

    public AuthBungeeCommand(CommandSpec definition, CommandService handler, MessageFormat messageFormat) {
        super(definition.name(), null, definition.aliases().toArray(String[]::new));
        this.root = definition.name();
        this.handler = handler;
        this.messageFormat = messageFormat;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        BungeeCommandSource source = new BungeeCommandSource(sender);
        handler.execute(new CommandContext(source, root, Arrays.asList(args)))
                .thenAccept(messages -> messages.forEach(message -> send(sender, message, messageFormat)));
    }

    private static void send(CommandSender sender, String message, MessageFormat format) {
        sender.sendMessage(BungeeMessages.components(message, format));
    }
}
