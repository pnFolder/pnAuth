package ru.privatenull.pnauth.dialog

import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.function.Consumer

/** A visible dialog which can be replaced or closed by its owner. */
interface DialogHandle : AutoCloseable {
    fun playerId(): UUID
    fun dialogId(): String
    fun active(): Boolean
    fun response(): CompletionStage<DialogResponse>
    fun onResponse(listener: Consumer<DialogResponse>): DialogSubscription
    fun replace(dialog: PlayerDialog)
    override fun close()
}
