package ru.privatenull.pnauth.velocity.dialog

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component

/** Transport boundary for Minecraft dialogs on Velocity. */
interface VelocityDialogService : AutoCloseable {
    fun available(): Boolean
    fun show(player: Player, form: DialogForm)
    fun clear(player: Player)

    override fun close() {}

    data class DialogForm(
        val title: Component,
        val notice: Component?,
        val fields: List<TextField>,
        val submitLabel: Component,
        val actionId: String
    )

    data class TextField(
        val key: String,
        val label: Component,
        val maxLength: Int
    )

    fun interface SubmissionHandler {
        fun submit(player: Player, actionId: String, values: Map<String, String>)
    }
}
