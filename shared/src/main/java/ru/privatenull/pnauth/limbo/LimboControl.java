package ru.privatenull.pnauth.limbo;

import java.util.UUID;
import java.util.Optional;

public interface LimboControl {
    int apiVersion();

    boolean isPlayerConnected(UUID playerId);

    LimboControlResult addBossBar(UUID playerId, UUID barId, String title, float progress,
                                  LimboBossBarColor color, LimboBossBarOverlay overlay);

    LimboControlResult updateBossBarProgress(UUID playerId, UUID barId, float progress);

    LimboControlResult updateBossBarTitle(UUID playerId, UUID barId, String title);

    LimboControlResult removeBossBar(UUID playerId, UUID barId);

    LimboControlResult showDialog(UUID playerId, String dialogJson);

    LimboControlResult clearDialog(UUID playerId);

    Optional<LimboDialogEvent> pollDialogEvent();
}
