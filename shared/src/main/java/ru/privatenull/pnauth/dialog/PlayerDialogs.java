package ru.privatenull.pnauth.dialog;

import ru.privatenull.pnauth.platform.PnPlayer;
import java.util.Optional;
import java.util.UUID;

/** Creates and manages dialogs independently from an authentication flow. */
public interface PlayerDialogs {
    boolean supported(PnPlayer player);
    DialogHandle show(PnPlayer player, PlayerDialog dialog);

    /** Opens a high-level form whose button action identifiers are generated and routed internally. */
    default DialogHandle show(PnPlayer player, DialogForm form) {
        return form.show(this, player);
    }

    /** Opens a high-level view of the links supplied through Minecraft's server-links packet. */
    default DialogHandle show(PnPlayer player, ServerLinksForm form) {
        return form.show(this, player);
    }
    Optional<DialogHandle> find(UUID playerId, String dialogId);
    boolean close(UUID playerId, String dialogId);
    void closeAll(UUID playerId);
}
