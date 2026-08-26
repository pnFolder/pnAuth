package ru.privatenull.pnauth.dialog

import net.kyori.adventure.text.Component

/** A button returned as the action identifier in a dialog response. */
@JvmRecord
data class DialogButton(
    val label: Component,
    val tooltip: Component = Component.empty(),
    val width: Int = 150,
    val action: DialogAction
) {
    constructor(label: Component, action: DialogAction) : this(label, Component.empty(), 150, action)
}
