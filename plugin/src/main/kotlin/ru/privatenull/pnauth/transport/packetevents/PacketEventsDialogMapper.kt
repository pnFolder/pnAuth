package ru.privatenull.pnauth.transport.packetevents

import com.github.retrooper.packetevents.protocol.chat.clickevent.ChangePageClickEvent
import com.github.retrooper.packetevents.protocol.chat.clickevent.ClickEvent
import com.github.retrooper.packetevents.protocol.chat.clickevent.CopyToClipboardClickEvent
import com.github.retrooper.packetevents.protocol.chat.clickevent.CustomClickEvent
import com.github.retrooper.packetevents.protocol.chat.clickevent.OpenUrlClickEvent
import com.github.retrooper.packetevents.protocol.chat.clickevent.RunCommandClickEvent
import com.github.retrooper.packetevents.protocol.chat.clickevent.ShowDialogClickEvent
import com.github.retrooper.packetevents.protocol.chat.clickevent.SuggestCommandClickEvent
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData
import com.github.retrooper.packetevents.protocol.dialog.ConfirmationDialog
import com.github.retrooper.packetevents.protocol.dialog.Dialog
import com.github.retrooper.packetevents.protocol.dialog.DialogListDialog
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog
import com.github.retrooper.packetevents.protocol.dialog.NoticeDialog
import com.github.retrooper.packetevents.protocol.dialog.ServerLinksDialog
import com.github.retrooper.packetevents.protocol.dialog.action.Action
import com.github.retrooper.packetevents.protocol.dialog.action.DialogTemplate
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicRunCommandAction
import com.github.retrooper.packetevents.protocol.dialog.action.StaticAction
import com.github.retrooper.packetevents.protocol.dialog.body.ItemDialogBody
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.Input
import com.github.retrooper.packetevents.protocol.dialog.input.InputControl
import com.github.retrooper.packetevents.protocol.dialog.input.NumberRangeInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.SingleOptionInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet
import com.github.retrooper.packetevents.protocol.nbt.NBT
import com.github.retrooper.packetevents.protocol.nbt.NBTByte
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTDouble
import com.github.retrooper.packetevents.protocol.nbt.NBTFloat
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.protocol.nbt.NBTList
import com.github.retrooper.packetevents.protocol.nbt.NBTLong
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber
import com.github.retrooper.packetevents.protocol.nbt.NBTShort
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.nbt.NBTType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.resources.ResourceLocation
import ru.privatenull.pnauth.dialog.DialogAction
import ru.privatenull.pnauth.dialog.DialogBody
import ru.privatenull.pnauth.dialog.DialogButton
import ru.privatenull.pnauth.dialog.DialogInput
import ru.privatenull.pnauth.dialog.DialogType
import ru.privatenull.pnauth.dialog.PlayerDialog

/** Lossless mapping from pnAuth dialogs to PacketEvents' vanilla protocol model. */
object PacketEventsDialogMapper {

    @JvmStatic
    fun map(source: PlayerDialog): Dialog {
        val common = common(source)
        return when (val type = source.type) {
            is DialogType.Notice -> NoticeDialog(common, button(type.action))
            is DialogType.Confirmation -> ConfirmationDialog(common, button(type.yes), button(type.no))
            is DialogType.MultiAction -> MultiActionDialog(
                common,
                buttons(type.actions),
                nullableButton(type.exitAction),
                type.columns
            )
            is DialogType.ServerLinks -> ServerLinksDialog(
                common,
                nullableButton(type.exitAction),
                type.columns,
                type.buttonWidth
            )
            is DialogType.DialogList -> {
                val references = if (type.dialogTag == null) {
                    MappedEntitySet(type.dialogs.map { map(it) })
                } else {
                    MappedEntitySet<Dialog>(ResourceLocation(type.dialogTag!!))
                }
                DialogListDialog(
                    common,
                    references,
                    nullableButton(type.exitAction),
                    type.columns,
                    type.buttonWidth
                )
            }
        }
    }

    private fun common(source: PlayerDialog): CommonDialogData {
        val layout = source.layout
        return CommonDialogData(
            layout.title,
            layout.externalTitle,
            layout.canCloseWithEscape,
            layout.pause,
            com.github.retrooper.packetevents.protocol.dialog.DialogAction.valueOf(layout.afterAction.name),
            bodies(layout.body),
            inputs(layout.inputs)
        )
    }

    private fun bodies(source: List<DialogBody>): List<com.github.retrooper.packetevents.protocol.dialog.body.DialogBody> {
        val result = mutableListOf<com.github.retrooper.packetevents.protocol.dialog.body.DialogBody>()
        for (body in source) {
            when (body) {
                is DialogBody.PlainMessage -> {
                    result.add(PlainMessageDialogBody(PlainMessage(body.content, body.width)))
                }
                is DialogBody.Item -> {
                    val description = body.description?.let { PlainMessage(it, body.descriptionWidth) }
                    @Suppress("DEPRECATION")
                    val item = ItemStack.decode(compound(body.itemStack), ClientVersion.V_1_21_6)
                    result.add(
                        ItemDialogBody(
                            item,
                            description,
                            body.showDecorations,
                            body.showTooltip,
                            body.width,
                            body.height
                        )
                    )
                }
            }
        }
        return result.toList()
    }

    private fun inputs(source: List<DialogInput>): List<Input> {
        val result = mutableListOf<Input>()
        for (input in source) {
            val control: InputControl = when (input) {
                is DialogInput.Text -> {
                    val multiline = if (input.multiline == null) null
                    else TextInputControl.MultilineOptions(
                        input.multiline!!.maximumLines,
                        input.multiline!!.height
                    )
                    TextInputControl(
                        input.width,
                        input.label(),
                        input.labelVisible,
                        input.initialValue,
                        input.maximumLength,
                        multiline
                    )
                }
                is DialogInput.Toggle -> {
                    BooleanInputControl(
                        input.label(),
                        input.initialValue,
                        input.onTrue,
                        input.onFalse
                    )
                }
                is DialogInput.Choice -> {
                    val options = input.options.map { option ->
                        SingleOptionInputControl.Entry(option.id, option.display, option.initial)
                    }
                    SingleOptionInputControl(
                        input.width,
                        options,
                        input.label(),
                        input.labelVisible
                    )
                }
                is DialogInput.NumberRange -> {
                    NumberRangeInputControl(
                        input.width,
                        input.label(),
                        input.labelFormat,
                        NumberRangeInputControl.RangeInfo(
                            input.start,
                            input.end,
                            input.initial,
                            input.step
                        )
                    )
                }
            }
            result.add(Input(input.id(), control))
        }
        return result.toList()
    }

    private fun buttons(source: List<DialogButton>): List<ActionButton> {
        return source.map { button(it) }
    }

    private fun nullableButton(source: DialogButton?): ActionButton? {
        return source?.let { button(it) }
    }

    private fun button(source: DialogButton): ActionButton {
        return ActionButton(
            CommonButtonData(source.label, source.tooltip, source.width),
            action(source.action)
        )
    }

    private fun action(source: DialogAction?): Action? {
        if (source == null || source is DialogAction.None) return null
        if (source is DialogAction.DynamicRunCommand) {
            return DynamicRunCommandAction(DialogTemplate(source.template))
        }
        if (source is DialogAction.DynamicCustom) {
            return DynamicCustomAction(ResourceLocation(source.id), compound(source.additions))
        }
        val fixed = source as DialogAction.Static
        return StaticAction(click(fixed.type, fixed.payload))
    }

    private fun click(rawType: String, payload: Map<String, Any>): ClickEvent {
        val type = rawType.replace("minecraft:", "")
        return when (type) {
            "open_url" -> OpenUrlClickEvent(string(payload, "url"))
            "run_command" -> RunCommandClickEvent(string(payload, "command"))
            "suggest_command" -> SuggestCommandClickEvent(string(payload, "command"))
            "change_page" -> ChangePageClickEvent(number(payload, "page").toInt())
            "copy_to_clipboard" -> CopyToClipboardClickEvent(string(payload, "value"))
            "custom" -> CustomClickEvent(ResourceLocation(string(payload, "id")), nbt(payload["payload"]))
            "show_dialog" -> ShowDialogClickEvent(map(payload["dialog"] as PlayerDialog))
            else -> throw IllegalArgumentException("Unsupported vanilla dialog action: $rawType")
        }
    }

    private fun compound(values: Map<String, Any?>): NBTCompound {
        val result = NBTCompound()
        values.forEach { (key, value) -> result.setTag(key, nbt(value)) }
        return result
    }

    private fun nbt(value: Any?): NBT {
        if (value == null) return NBTCompound()
        if (value is NBT) return value
        if (value is Map<*, *>) {
            val result = NBTCompound()
            value.forEach { (key, child) -> result.setTag(key.toString(), nbt(child)) }
            return result
        }
        if (value is List<*>) {
            val values = value.map { nbt(it) }
            @Suppress("UNCHECKED_CAST")
            return NBTList(NBTList.getCommonTagType(values) as NBTType<NBT>, values)
        }
        if (value is Boolean) return NBTByte(if (value) 1.toByte() else 0.toByte())
        if (value is Byte) return NBTByte(value)
        if (value is Short) return NBTShort(value)
        if (value is Int) return NBTInt(value)
        if (value is Long) return NBTLong(value)
        if (value is Float) return NBTFloat(value)
        if (value is Double) return NBTDouble(value)
        return NBTString(value.toString())
    }

    private fun string(payload: Map<String, Any>, key: String): String {
        val value = payload[key] ?: throw IllegalArgumentException("Missing action field: $key")
        return value.toString()
    }

    private fun number(payload: Map<String, Any>, key: String): Number {
        val value = payload[key]
        if (value is Number) return value
        throw IllegalArgumentException("Action field is not numeric: $key")
    }
}
