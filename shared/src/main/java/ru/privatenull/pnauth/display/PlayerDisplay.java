package ru.privatenull.pnauth.display;

import java.util.Optional;
import java.util.UUID;

/**
 * Manages stateful player UI elements by their stable display identifiers.
 * Creating an element with an existing identifier updates the existing handle.
 */
public interface PlayerDisplay {
    ActionBarHandle actionBar(UUID playerId, String displayId, ActionBarOptions options);

    TitleHandle title(UUID playerId, String displayId, TitleOptions options);

    BossBarHandle bossBar(UUID playerId, String displayId, BossBarOptions options);

    Optional<ActionBarHandle> findActionBar(UUID playerId, String displayId);

    Optional<TitleHandle> findTitle(UUID playerId, String displayId);

    Optional<BossBarHandle> findBossBar(UUID playerId, String displayId);

    boolean removeActionBar(UUID playerId, String displayId);

    boolean removeTitle(UUID playerId, String displayId);

    boolean removeBossBar(UUID playerId, String displayId);

    void clear(UUID playerId);

    default ActionBarHandle showActionBar(UUID playerId, ActionBarOptions options) {
        return actionBar(playerId, UUID.randomUUID().toString(), options);
    }

    default TitleHandle showTitle(UUID playerId, TitleOptions options) {
        return title(playerId, UUID.randomUUID().toString(), options);
    }

    default BossBarHandle showBossBar(UUID playerId, BossBarOptions options) {
        return bossBar(playerId, UUID.randomUUID().toString(), options);
    }
}
