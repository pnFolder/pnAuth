package ru.privatenull.pnauth.dialog;

import ru.privatenull.pnauth.platform.PnPlayer;
import java.util.Optional;
import java.util.UUID;

/** Dialog service used when native dialogs are unavailable. */
public final class UnsupportedPlayerDialogs implements PlayerDialogs {
    @Override public boolean supported(PnPlayer player) { return false; }
    @Override public DialogHandle show(PnPlayer player, PlayerDialog dialog) {
        throw new UnsupportedOperationException("Native player dialogs are not supported by this adapter");
    }
    @Override public Optional<DialogHandle> find(UUID playerId, String dialogId) { return Optional.empty(); }
    @Override public boolean close(UUID playerId, String dialogId) { return false; }
    @Override public void closeAll(UUID playerId) { }
}
