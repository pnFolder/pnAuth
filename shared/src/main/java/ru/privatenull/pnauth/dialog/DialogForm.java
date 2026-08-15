package ru.privatenull.pnauth.dialog;

import net.kyori.adventure.text.Component;
import ru.privatenull.pnauth.platform.PnPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * High-level native form API. Transport identifiers are generated per display and button
 * responses are routed directly to their handlers.
 */
public final class DialogForm {
    private final DialogLayout layout;
    private final List<Button> buttons;
    private final int columns;
    private final Consumer<DialogResponse> closeHandler;

    private DialogForm(Builder builder) {
        this.layout = new DialogLayout(builder.title, builder.externalTitle, builder.body, builder.inputs,
                builder.canCloseWithEscape, builder.pause, builder.afterAction);
        this.buttons = List.copyOf(builder.buttons);
        this.columns = builder.columns;
        this.closeHandler = builder.closeHandler;
    }

    public static Builder builder(Component title) {
        return new Builder(title);
    }

    DialogHandle show(PlayerDialogs dialogs, PnPlayer player) {
        Objects.requireNonNull(dialogs, "dialogs");
        Objects.requireNonNull(player, "player");
        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Consumer<DialogResponse>> handlers = new LinkedHashMap<>();
        List<DialogButton> materialized = new ArrayList<>(buttons.size());
        for (int index = 0; index < buttons.size(); index++) {
            Button button = buttons.get(index);
            String actionId = "pnauth:form_" + token + "_" + index;
            handlers.put(actionId, button.handler);
            materialized.add(new DialogButton(button.label, button.tooltip, button.width,
                    new DialogAction.DynamicCustom(actionId, Map.of())));
        }
        PlayerDialog dialog = new PlayerDialog("pnauth:form_" + token, layout,
                new DialogType.MultiAction(materialized, null, columns));
        DialogHandle handle = dialogs.show(player, dialog);
        handle.onResponse(response -> {
            if (response.closed()) {
                if (closeHandler != null) closeHandler.accept(response);
                return;
            }
            Consumer<DialogResponse> handler = handlers.get(response.action());
            if (handler != null) handler.accept(response);
        });
        return handle;
    }

    private record Button(Component label, Component tooltip, int width, Consumer<DialogResponse> handler) { }

    public static final class Builder {
        private final Component title;
        private Component externalTitle;
        private final List<DialogBody> body = new ArrayList<>();
        private final List<DialogInput> inputs = new ArrayList<>();
        private final List<Button> buttons = new ArrayList<>();
        private boolean canCloseWithEscape = true;
        private boolean pause;
        private DialogLayout.AfterAction afterAction = DialogLayout.AfterAction.CLOSE;
        private int columns = 1;
        private Consumer<DialogResponse> closeHandler;

        private Builder(Component title) {
            this.title = Objects.requireNonNull(title, "title");
        }

        public Builder externalTitle(Component value) { externalTitle = value; return this; }

        public Builder body(Component content, int width) {
            body.add(new DialogBody.PlainMessage(Objects.requireNonNull(content, "content"), width));
            return this;
        }

        public Builder input(DialogInput input) {
            inputs.add(Objects.requireNonNull(input, "input"));
            return this;
        }

        public Builder text(String id, Component label, int maximumLength, int width) {
            return input(new DialogInput.Text(id, label, true, "", maximumLength, width, null));
        }

        public Builder toggle(String id, Component label, boolean initialValue) {
            return input(new DialogInput.Toggle(id, label, initialValue, null, null));
        }

        public Builder button(Component label, Consumer<DialogResponse> handler) {
            return button(label, Component.empty(), 150, handler);
        }

        public Builder button(Component label, Component tooltip, int width,
                              Consumer<DialogResponse> handler) {
            buttons.add(new Button(Objects.requireNonNull(label, "label"),
                    tooltip == null ? Component.empty() : tooltip, width,
                    Objects.requireNonNull(handler, "handler")));
            return this;
        }

        public Builder onClose(Consumer<DialogResponse> handler) { closeHandler = handler; return this; }
        public Builder columns(int value) { columns = value; return this; }
        public Builder canCloseWithEscape(boolean value) { canCloseWithEscape = value; return this; }
        public Builder pause(boolean value) { pause = value; return this; }
        public Builder afterAction(DialogLayout.AfterAction value) { afterAction = value; return this; }

        public DialogForm build() {
            if (buttons.isEmpty()) throw new IllegalStateException("A form must contain at least one button");
            if (columns < 1) throw new IllegalStateException("Form columns must be positive");
            return new DialogForm(this);
        }
    }
}
