package ru.privatenull.pnauth.paper;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.privatenull.pnauth.command.CommandSource;
import java.util.UUID;

/** Adapts Bukkit command senders to the shared command model. */
final class PaperCommandSource implements CommandSource {
    private final CommandSender sender;

    PaperCommandSource(CommandSender sender) { this.sender = sender; }
    @Override public UUID uniqueId() { return sender instanceof Player player ? player.getUniqueId() : null; }
    @Override public String username() { return sender instanceof Player player ? player.getName() : null; }
    @Override public boolean isPlayer() { return sender instanceof Player; }
    @Override public boolean hasPermission(String permission) { return sender.hasPermission(permission); }
}
