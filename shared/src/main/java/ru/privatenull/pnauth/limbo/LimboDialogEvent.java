package ru.privatenull.pnauth.limbo;

import java.util.Objects;
import java.util.UUID;

public record LimboDialogEvent(UUID playerId, String actionId, String dataJson) {
    public LimboDialogEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(dataJson, "dataJson");
    }
}
