package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import ru.privatenull.pnauth.command.CommandSource;

import java.util.UUID;

final class BungeeCommandSource implements CommandSource {
    private final CommandSender sender;

    BungeeCommandSource(CommandSender sender) {
        this.sender = sender;
    }

    @Override
    public UUID uniqueId() {
        return sender instanceof ProxiedPlayer player ? player.getUniqueId() : null;
    }

    @Override
    public String username() {
        return sender instanceof ProxiedPlayer player ? player.getName() : null;
    }

    @Override
    public boolean isPlayer() {
        return sender instanceof ProxiedPlayer;
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }

    CommandSender sender() {
        return sender;
    }
}
