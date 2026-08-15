package ru.privatenull.pnauth.dialog

import net.kyori.adventure.text.Component

/** Content element rendered in a native Minecraft dialog body. */
sealed interface DialogBody {
    @JvmRecord
    data class PlainMessage(val content: Component, val width: Int) : DialogBody

    @JvmRecord
    data class Item(
        val itemStack: Map<String, Any>,
        val description: Component,
        val descriptionWidth: Int,
        val showDecorations: Boolean,
        val showTooltip: Boolean,
        val width: Int,
        val height: Int
    ) : DialogBody
}
