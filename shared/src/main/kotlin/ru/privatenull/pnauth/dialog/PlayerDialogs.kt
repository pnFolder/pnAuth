package ru.privatenull.pnauth.dialog

import ru.privatenull.pnauth.platform.PnPlayer
import java.util.Optional
import java.util.UUID

/** Creates and manages dialogs independently from an authentication flow. */
interface PlayerDialogs {
    fun supported(player: PnPlayer): Boolean
    fun show(player: PnPlayer, dialog: PlayerDialog): DialogHandle

    /** Opens a high-level form whose button action identifiers are generated and routed internally. */
    fun show(player: PnPlayer, form: DialogForm): DialogHandle {
        return form.show(this, player)
    }

    fun find(playerId: UUID, dialogId: String): Optional<DialogHandle>
    fun close(playerId: UUID, dialogId: String): Boolean
    fun closeAll(playerId: UUID)
}
