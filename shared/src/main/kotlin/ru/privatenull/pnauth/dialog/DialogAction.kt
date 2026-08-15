package ru.privatenull.pnauth.dialog

/** Every vanilla dialog action, including the two input-aware dynamic variants. */
sealed interface DialogAction {
    @JvmRecord
    data class None(val unused: Unit = Unit) : DialogAction {
        constructor() : this(Unit)
    }

    @JvmRecord
    data class Static(val type: String, val payload: Map<String, Any> = emptyMap()) : DialogAction

    @JvmRecord
    data class DynamicRunCommand(val template: String) : DialogAction

    @JvmRecord
    data class DynamicCustom(val id: String, val additions: Map<String, Any> = emptyMap()) : DialogAction
}
