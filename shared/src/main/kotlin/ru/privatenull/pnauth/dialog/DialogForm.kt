package ru.privatenull.pnauth.dialog

import net.kyori.adventure.text.Component
import ru.privatenull.pnauth.platform.PnPlayer
import java.util.UUID
import java.util.function.Consumer

/**
 * High-level native form API. Transport identifiers are generated per display and button
 * responses are routed directly to their handlers.
 */
class DialogForm private constructor(builder: Builder) {
    val layout: DialogLayout = DialogLayout(
        builder.title, builder.externalTitle, builder.body, builder.inputs,
        builder.canCloseWithEscape, builder.pause, builder.afterAction
    )
    val buttons: List<Button> = builder.buttons.toList()
    val columns: Int = builder.columns
    val closeHandler: Consumer<DialogResponse>? = builder.closeHandler

    internal fun show(dialogs: PlayerDialogs, player: PnPlayer): DialogHandle {
        val token = UUID.randomUUID().toString().replace("-", "")
        val handlers = LinkedHashMap<String, Consumer<DialogResponse>>()
        val materialized = ArrayList<DialogButton>(buttons.size)
        for (index in buttons.indices) {
            val button = buttons[index]
            val actionId = "pnauth:form_${token}_$index"
            handlers[actionId] = button.handler
            materialized.add(
                DialogButton(
                    button.label, button.tooltip, button.width,
                    DialogAction.DynamicCustom(actionId, emptyMap())
                )
            )
        }
        val dialog = PlayerDialog(
            "pnauth:form_$token", layout,
            DialogType.MultiAction(materialized, null, columns)
        )
        val handle = dialogs.show(player, dialog)
        handle.onResponse { response ->
            if (response.closed) {
                closeHandler?.accept(response)
                return@onResponse
            }
            val handler = handlers[response.action]
            handler?.accept(response)
        }
        return handle
    }

    data class Button(
        val label: Component,
        val tooltip: Component,
        val width: Int,
        val handler: Consumer<DialogResponse>
    )

    class Builder(val title: Component) {
        var externalTitle: Component? = null
            private set
        val body: MutableList<DialogBody> = ArrayList()
        val inputs: MutableList<DialogInput> = ArrayList()
        private val _buttons: MutableList<Button> = ArrayList()
        internal val buttons: List<Button> get() = _buttons
        var canCloseWithEscape: Boolean = true
            private set
        var pause: Boolean = false
            private set
        var afterAction: DialogLayout.AfterAction = DialogLayout.AfterAction.CLOSE
            private set
        var columns: Int = 1
            private set
        var closeHandler: Consumer<DialogResponse>? = null
            private set

        fun externalTitle(value: Component?): Builder {
            externalTitle = value
            return this
        }

        fun body(content: Component, width: Int): Builder {
            body.add(DialogBody.PlainMessage(content, width))
            return this
        }

        fun input(input: DialogInput): Builder {
            inputs.add(input)
            return this
        }

        fun text(id: String, label: Component, maximumLength: Int, width: Int): Builder {
            return input(DialogInput.Text(id, label, true, "", maximumLength, width, null))
        }

        fun toggle(id: String, label: Component, initialValue: Boolean): Builder {
            return input(DialogInput.Toggle(id, label, initialValue, null, null))
        }

        fun button(label: Component, handler: Consumer<DialogResponse>): Builder {
            return button(label, Component.empty(), 150, handler)
        }

        fun button(
            label: Component,
            tooltip: Component?,
            width: Int,
            handler: Consumer<DialogResponse>
        ): Builder {
            _buttons.add(Button(label, tooltip ?: Component.empty(), width, handler))
            return this
        }

        fun onClose(handler: Consumer<DialogResponse>?): Builder {
            closeHandler = handler
            return this
        }

        fun columns(value: Int): Builder {
            columns = value
            return this
        }

        fun canCloseWithEscape(value: Boolean): Builder {
            canCloseWithEscape = value
            return this
        }

        fun pause(value: Boolean): Builder {
            pause = value
            return this
        }

        fun afterAction(value: DialogLayout.AfterAction): Builder {
            afterAction = value
            return this
        }

        fun build(): DialogForm {
            check(_buttons.isNotEmpty()) { "A form must contain at least one button" }
            check(columns >= 1) { "Form columns must be positive" }
            return DialogForm(this)
        }
    }

    companion object {
        @JvmStatic
        fun builder(title: Component): Builder {
            return Builder(title)
        }
    }
}
