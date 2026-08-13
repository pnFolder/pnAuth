package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import java.util.UUID;

final class VelocityCommandSource implements ru.privatenull.pnauth.command.CommandSource {
    private final CommandSource source;

    VelocityCommandSource(CommandSource source) {
        this.source = source;
    }

    @Override
    public UUID uniqueId() {
        return source instanceof Player player ? player.getUniqueId() : null;
    }

    @Override
    public String username() {
        return source instanceof Player player ? player.getUsername() : null;
    }

    @Override
    public boolean isPlayer() {
        return source instanceof Player;
    }

    @Override
    public boolean hasPermission(String permission) {
        return source.hasPermission(permission);
    }

    CommandSource source() {
        return source;
    }
}
