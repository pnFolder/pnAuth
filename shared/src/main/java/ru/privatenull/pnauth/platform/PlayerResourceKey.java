package ru.privatenull.pnauth.platform;

import java.util.UUID;

/** Shared identity for every named resource owned by a player. */
public final class PlayerResourceKey {
    public final UUID playerId;
    public final String name;

    public PlayerResourceKey(UUID playerId, String resourceId) {
        if (playerId == null) throw new IllegalArgumentException("playerId");
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("resourceId");
        this.playerId = playerId;
        this.name = resourceId;
    }

    public UUID playerId() { return playerId; }
    public String resourceId() { return name; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof PlayerResourceKey key
                && playerId.equals(key.playerId) && name.equals(key.name);
    }

    @Override public int hashCode() { return 31 * playerId.hashCode() + name.hashCode(); }
}
