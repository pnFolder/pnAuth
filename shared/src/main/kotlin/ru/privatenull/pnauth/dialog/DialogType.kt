package ru.privatenull.pnauth.dialog

/** Exact type-specific footer/layout variants supported by vanilla Minecraft. */
sealed interface DialogType {
    @JvmRecord
    data class Notice(val action: DialogButton) : DialogType

    @JvmRecord
    data class Confirmation(val yes: DialogButton, val no: DialogButton) : DialogType

    @JvmRecord
    data class MultiAction(
        val actions: List<DialogButton>,
        val exitAction: DialogButton?,
        val columns: Int
    ) : DialogType

    @JvmRecord
    data class ServerLinks(val exitAction: DialogButton?, val columns: Int, val buttonWidth: Int) : DialogType

    @JvmRecord
    data class DialogList(
        val dialogs: List<PlayerDialog>,
        val dialogTag: String?,
        val exitAction: DialogButton?,
        val columns: Int,
        val buttonWidth: Int
    ) : DialogType
}
