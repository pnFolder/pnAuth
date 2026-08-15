package ru.privatenull.pnauth.transport.packetevents;

import com.github.retrooper.packetevents.protocol.chat.clickevent.*;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.ConfirmationDialog;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.NoticeDialog;
import com.github.retrooper.packetevents.protocol.dialog.ServerLinksDialog;
import com.github.retrooper.packetevents.protocol.dialog.DialogListDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicRunCommandAction;
import com.github.retrooper.packetevents.protocol.dialog.action.DialogTemplate;
import com.github.retrooper.packetevents.protocol.dialog.action.StaticAction;
import com.github.retrooper.packetevents.protocol.dialog.body.ItemDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData;
import com.github.retrooper.packetevents.protocol.dialog.input.*;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import ru.privatenull.pnauth.dialog.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lossless mapping from pnAuth dialogs to PacketEvents' vanilla protocol model. */
public final class PacketEventsDialogMapper {
    private PacketEventsDialogMapper() { }

    public static Dialog map(PlayerDialog source) {
        CommonDialogData common = common(source);
        if (source.type() instanceof DialogType.Notice notice) {
            return new NoticeDialog(common, button(notice.action()));
        }
        if (source.type() instanceof DialogType.Confirmation confirmation) {
            return new ConfirmationDialog(common, button(confirmation.yes()), button(confirmation.no()));
        }
        if (source.type() instanceof DialogType.MultiAction multi) {
            return new MultiActionDialog(common, buttons(multi.actions()), nullableButton(multi.exitAction()), multi.columns());
        }
        if (source.type() instanceof DialogType.ServerLinks links) {
            return new ServerLinksDialog(common, nullableButton(links.exitAction()), links.columns(), links.buttonWidth());
        }
        DialogType.DialogList list = (DialogType.DialogList) source.type();
        var references = list.dialogTag() == null
                ? new MappedEntitySet<>(list.dialogs().stream().map(PacketEventsDialogMapper::map).toList())
                : new MappedEntitySet<Dialog>(new ResourceLocation(list.dialogTag()));
        return new DialogListDialog(common, references, nullableButton(list.exitAction()),
                list.columns(), list.buttonWidth());
    }

    private static CommonDialogData common(PlayerDialog source) {
        var layout = source.layout();
        return new CommonDialogData(layout.title(), layout.externalTitle(), layout.canCloseWithEscape(),
                layout.pause(), com.github.retrooper.packetevents.protocol.dialog.DialogAction.valueOf(
                        layout.afterAction().name()), bodies(layout.body()), inputs(layout.inputs()));
    }

    private static List<com.github.retrooper.packetevents.protocol.dialog.body.DialogBody> bodies(
            List<DialogBody> source) {
        List<com.github.retrooper.packetevents.protocol.dialog.body.DialogBody> result = new ArrayList<>();
        for (DialogBody body : source) {
            if (body instanceof DialogBody.PlainMessage message) {
                result.add(new PlainMessageDialogBody(new PlainMessage(message.content(), message.width())));
            } else if (body instanceof DialogBody.Item item) {
                PlainMessage description = item.description() == null ? null
                        : new PlainMessage(item.description(), item.descriptionWidth());
                result.add(new ItemDialogBody(ItemStack.decode(compound(item.itemStack()), ClientVersion.V_1_21_6),
                        description, item.showDecorations(), item.showTooltip(), item.width(), item.height()));
            }
        }
        return List.copyOf(result);
    }

    private static List<Input> inputs(List<DialogInput> source) {
        List<Input> result = new ArrayList<>();
        for (DialogInput input : source) {
            InputControl control;
            if (input instanceof DialogInput.Text text) {
                var multiline = text.multiline() == null ? null : new TextInputControl.MultilineOptions(
                        text.multiline().maximumLines(), text.multiline().height());
                control = new TextInputControl(text.width(), text.label(), text.labelVisible(),
                        text.initialValue(), text.maximumLength(), multiline);
            } else if (input instanceof DialogInput.Toggle toggle) {
                control = new BooleanInputControl(toggle.label(), toggle.initialValue(),
                        toggle.onTrue(), toggle.onFalse());
            } else if (input instanceof DialogInput.Choice choice) {
                List<SingleOptionInputControl.Entry> options = choice.options().stream()
                        .map(option -> new SingleOptionInputControl.Entry(
                                option.id(), option.display(), option.initial())).toList();
                control = new SingleOptionInputControl(choice.width(), options,
                        choice.label(), choice.labelVisible());
            } else {
                DialogInput.NumberRange range = (DialogInput.NumberRange) input;
                control = new NumberRangeInputControl(range.width(), range.label(), range.labelFormat(),
                        new NumberRangeInputControl.RangeInfo(
                                range.start(), range.end(), range.initial(), range.step()));
            }
            result.add(new Input(input.id(), control));
        }
        return List.copyOf(result);
    }

    private static List<ActionButton> buttons(List<DialogButton> source) {
        return source.stream().map(PacketEventsDialogMapper::button).toList();
    }

    private static ActionButton nullableButton(DialogButton source) {
        return source == null ? null : button(source);
    }

    private static ActionButton button(DialogButton source) {
        return new ActionButton(new CommonButtonData(source.label(), source.tooltip(), source.width()),
                action(source.action()));
    }

    private static com.github.retrooper.packetevents.protocol.dialog.action.Action action(DialogAction source) {
        if (source == null || source instanceof DialogAction.None) return null;
        if (source instanceof DialogAction.DynamicRunCommand command) {
            return new DynamicRunCommandAction(new DialogTemplate(command.template()));
        }
        if (source instanceof DialogAction.DynamicCustom custom) {
            return new DynamicCustomAction(new ResourceLocation(custom.id()), compound(custom.additions()));
        }
        DialogAction.Static fixed = (DialogAction.Static) source;
        return new StaticAction(click(fixed.type(), fixed.payload()));
    }

    private static ClickEvent click(String rawType, Map<String, Object> payload) {
        String type = rawType.replace("minecraft:", "");
        return switch (type) {
            case "open_url" -> new OpenUrlClickEvent(string(payload, "url"));
            case "run_command" -> new RunCommandClickEvent(string(payload, "command"));
            case "suggest_command" -> new SuggestCommandClickEvent(string(payload, "command"));
            case "change_page" -> new ChangePageClickEvent(number(payload, "page").intValue());
            case "copy_to_clipboard" -> new CopyToClipboardClickEvent(string(payload, "value"));
            case "custom" -> new CustomClickEvent(new ResourceLocation(string(payload, "id")), nbt(payload.get("payload")));
            case "show_dialog" -> new ShowDialogClickEvent(map((PlayerDialog) payload.get("dialog")));
            default -> throw new IllegalArgumentException("Unsupported vanilla dialog action: " + rawType);
        };
    }

    private static NBTCompound compound(Map<String, Object> values) {
        NBTCompound result = new NBTCompound();
        values.forEach((key, value) -> result.setTag(key, nbt(value)));
        return result;
    }

    private static NBT nbt(Object value) {
        if (value == null) return new NBTCompound();
        if (value instanceof NBT existing) return existing;
        if (value instanceof Map<?, ?> map) {
            NBTCompound result = new NBTCompound();
            map.forEach((key, child) -> result.setTag(String.valueOf(key), nbt(child)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<NBT> values = list.stream().map(PacketEventsDialogMapper::nbt).toList();
            @SuppressWarnings({"rawtypes", "unchecked"})
            NBTList<NBT> result = new NBTList((com.github.retrooper.packetevents.protocol.nbt.NBTType)
                    NBTList.getCommonTagType(values), values);
            return result;
        }
        if (value instanceof Boolean bool) return new NBTByte((byte) (bool ? 1 : 0));
        if (value instanceof Byte number) return new NBTByte(number);
        if (value instanceof Short number) return new NBTShort(number);
        if (value instanceof Integer number) return new NBTInt(number);
        if (value instanceof Long number) return new NBTLong(number);
        if (value instanceof Float number) return new NBTFloat(number);
        if (value instanceof Double number) return new NBTDouble(number);
        return new NBTString(String.valueOf(value));
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) throw new IllegalArgumentException("Missing action field: " + key);
        return String.valueOf(value);
    }

    private static Number number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("Action field is not numeric: " + key);
    }
}
