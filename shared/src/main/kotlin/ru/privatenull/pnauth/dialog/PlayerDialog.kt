package ru.privatenull.pnauth.dialog

/** Complete platform-neutral description of a vanilla Minecraft dialog. */
@JvmRecord
data class PlayerDialog(val id: String, val layout: DialogLayout, val type: DialogType)
