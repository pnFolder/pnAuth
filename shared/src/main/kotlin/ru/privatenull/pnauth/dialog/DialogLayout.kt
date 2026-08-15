package ru.privatenull.pnauth.dialog

import net.kyori.adventure.text.Component

/** Fields shared by every vanilla dialog type. */
@JvmRecord
data class DialogLayout @JvmOverloads constructor(
    val title: Component,
    val externalTitle: Component? = title,
    val body: List<DialogBody> = emptyList(),
    val inputs: List<DialogInput> = emptyList(),
    val canCloseWithEscape: Boolean = true,
    val pause: Boolean = false,
    val afterAction: AfterAction = AfterAction.CLOSE
) {
    enum class AfterAction { CLOSE, NONE, WAIT_FOR_RESPONSE }
}
