package ru.privatenull.pnauth.transport.packetevents

import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.NumberRangeInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.SingleOptionInputControl
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.dialog.DialogAction
import ru.privatenull.pnauth.dialog.DialogBody
import ru.privatenull.pnauth.dialog.DialogButton
import ru.privatenull.pnauth.dialog.DialogInput
import ru.privatenull.pnauth.dialog.DialogLayout
import ru.privatenull.pnauth.dialog.DialogType
import ru.privatenull.pnauth.dialog.PlayerDialog

class PacketEventsDialogMapperTest {
    @Test
    fun mapsEveryInputAndCommonLayoutField() {
        val source = PlayerDialog(
            "test:complete",
            DialogLayout(
                Component.text("Title"),
                Component.text("External"),
                listOf(DialogBody.PlainMessage(Component.text("Body"), 420)),
                listOf(
                    DialogInput.Text(
                        "text", Component.text("Text"), false, "initial", 80, 300,
                        DialogInput.Text.Multiline(5, 120)
                    ),
                    DialogInput.Toggle("enabled", Component.text("Enabled"), true, "yes", "no"),
                    DialogInput.Choice(
                        "choice", Component.text("Choice"), true, 250,
                        listOf(DialogInput.Choice.Option("one", Component.text("One"), true))
                    ),
                    DialogInput.NumberRange(
                        "amount", Component.text("Amount"),
                        "options.generic_value", 300, 0f, 100f, 50f, 5f
                    )
                ),
                false, false, DialogLayout.AfterAction.WAIT_FOR_RESPONSE
            ),
            DialogType.MultiAction(
                listOf(
                    DialogButton(
                        Component.text("Submit"), Component.text("Tooltip"), 180,
                        DialogAction.DynamicCustom("test:submit", mapOf("source" to "test"))
                    )
                ),
                null, 3
            )
        )

        val mapped = assertInstanceOf(MultiActionDialog::class.java, PacketEventsDialogMapper.map(source))

        assertFalse(mapped.common.isCanCloseWithEscape)
        assertEquals(4, mapped.common.inputs.size)
        assertInstanceOf(TextInputControl::class.java, mapped.common.inputs[0].control)
        assertInstanceOf(BooleanInputControl::class.java, mapped.common.inputs[1].control)
        assertInstanceOf(SingleOptionInputControl::class.java, mapped.common.inputs[2].control)
        assertInstanceOf(NumberRangeInputControl::class.java, mapped.common.inputs[3].control)
        assertEquals(3, mapped.columns)
        assertInstanceOf(DynamicCustomAction::class.java, mapped.actions[0].action)
    }
}
