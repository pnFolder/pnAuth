package ru.privatenull.pnauth.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.privatenull.pnauth.command.CommandContext;
import ru.privatenull.pnauth.command.CommandService;

import java.util.Arrays;
import java.util.List;

/** Thin Paper/Folia command adapter; command behavior lives in the shared service. */
final class PaperAuthCommand implements CommandExecutor, TabCompleter {
    private final CommandService commands;

    PaperAuthCommand(CommandService commands) { this.commands = commands; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] arguments) {
        CommandContext context = new CommandContext(
                new PaperCommandSource(sender), command.getName(), Arrays.asList(arguments));
        commands.execute(context).thenAccept(messages -> messages.forEach(sender::sendMessage));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] arguments) {
        return commands.suggest(new CommandContext(
                new PaperCommandSource(sender), command.getName(), Arrays.asList(arguments)));
    }
}
