package ru.privatenull.pnauth.dialog

import ru.privatenull.pnauth.platform.Player
import java.util.Optional
import java.util.UUID

/** Dialog service used when native dialogs are unavailable. */
class UnsupportedPlayerDialogs : PlayerDialogs {
    override fun supported(player: Player): Boolean = false

    override fun show(player: Player, dialog: PlayerDialog): DialogHandle {
        throw UnsupportedOperationException("Native player dialogs are not supported by this adapter")
    }

    override fun find(playerId: UUID, dialogId: String): Optional<DialogHandle> = Optional.empty()

    override fun close(playerId: UUID, dialogId: String): Boolean = false

    override fun closeAll(playerId: UUID) {}
}
