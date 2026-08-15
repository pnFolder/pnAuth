package ru.privatenull.pnauth.transport.packetevents;

import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.input.BooleanInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.NumberRangeInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.SingleOptionInputControl;
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.dialog.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PacketEventsDialogMapperTest {
    @Test
    void mapsEveryInputAndCommonLayoutField() {
        PlayerDialog source = new PlayerDialog("test:complete", new DialogLayout(
                Component.text("Title"), Component.text("External"),
                List.of(new DialogBody.PlainMessage(Component.text("Body"), 420)),
                List.of(
                        new DialogInput.Text("text", Component.text("Text"), false, "initial", 80, 300,
                                new DialogInput.Text.Multiline(5, 120)),
                        new DialogInput.Toggle("enabled", Component.text("Enabled"), true, "yes", "no"),
                        new DialogInput.Choice("choice", Component.text("Choice"), true, 250,
                                List.of(new DialogInput.Choice.Option("one", Component.text("One"), true))),
                        new DialogInput.NumberRange("amount", Component.text("Amount"),
                                "options.generic_value", 300, 0F, 100F, 50F, 5F)
                ), false, false, DialogLayout.AfterAction.WAIT_FOR_RESPONSE
        ), new DialogType.MultiAction(List.of(
                new DialogButton(Component.text("Submit"), Component.text("Tooltip"), 180,
                        new DialogAction.DynamicCustom("test:submit", Map.of("source", "test")))
        ), null, 3));

        MultiActionDialog mapped = assertInstanceOf(MultiActionDialog.class,
                PacketEventsDialogMapper.map(source));

        assertFalse(mapped.getCommon().isCanCloseWithEscape());
        assertEquals(4, mapped.getCommon().getInputs().size());
        assertInstanceOf(TextInputControl.class, mapped.getCommon().getInputs().get(0).getControl());
        assertInstanceOf(BooleanInputControl.class, mapped.getCommon().getInputs().get(1).getControl());
        assertInstanceOf(SingleOptionInputControl.class, mapped.getCommon().getInputs().get(2).getControl());
        assertInstanceOf(NumberRangeInputControl.class, mapped.getCommon().getInputs().get(3).getControl());
        assertEquals(3, mapped.getColumns());
        assertInstanceOf(DynamicCustomAction.class, mapped.getActions().get(0).getAction());
    }
}
