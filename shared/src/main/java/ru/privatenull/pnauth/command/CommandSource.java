package ru.privatenull.pnauth.command;

import java.util.UUID;

public interface CommandSource {
    UUID uniqueId();

    String username();

    boolean isPlayer();

    boolean hasPermission(String permission);
}
