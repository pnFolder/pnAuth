package ru.privatenull.pnauth.dialog;

import net.kyori.adventure.text.Component;
import ru.privatenull.pnauth.platform.PnPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** High-level builder for Minecraft's native server-links dialog. */
public final class ServerLinksForm {
    private final DialogLayout layout;
    private final int columns;
    private final int buttonWidth;
    private final ExitButton exitButton;
    private final Consumer<DialogResponse> closeHandler;

    private ServerLinksForm(Builder builder) {
        layout = new DialogLayout(builder.title, builder.externalTitle, builder.body, List.of(),
                builder.canCloseWithEscape, builder.pause, builder.afterAction);
        columns = builder.columns;
        buttonWidth = builder.buttonWidth;
        exitButton = builder.exitButton;
        closeHandler = builder.closeHandler;
    }

    public static Builder builder(Component title) {
        return new Builder(title);
    }

    DialogHandle show(PlayerDialogs dialogs, PnPlayer player) {
        Objects.requireNonNull(dialogs, "dialogs");
        Objects.requireNonNull(player, "player");
        String token = UUID.randomUUID().toString().replace("-", "");
        String actionId = exitButton == null ? null : "pnauth:server_links_exit_" + token;
        DialogButton exit = exitButton == null ? null : new DialogButton(
                exitButton.label, exitButton.tooltip, exitButton.width,
                new DialogAction.DynamicCustom(actionId, Map.of()));
        PlayerDialog dialog = new PlayerDialog("pnauth:server_links_" + token, layout,
                new DialogType.ServerLinks(exit, columns, buttonWidth));
        DialogHandle handle = dialogs.show(player, dialog);
        handle.onResponse(response -> {
            if (response.closed()) {
                if (closeHandler != null) closeHandler.accept(response);
            } else if (exitButton != null && actionId.equals(response.action())) {
                exitButton.handler.accept(response);
            }
        });
        return handle;
    }

    private record ExitButton(Component label, Component tooltip, int width,
                              Consumer<DialogResponse> handler) { }

    public static final class Builder {
        private final Component title;
        private Component externalTitle;
        private final List<DialogBody> body = new ArrayList<>();
        private boolean canCloseWithEscape = true;
        private boolean pause;
        private DialogLayout.AfterAction afterAction = DialogLayout.AfterAction.CLOSE;
        private int columns = 2;
        private int buttonWidth = 200;
        private ExitButton exitButton;
        private Consumer<DialogResponse> closeHandler;

        private Builder(Component title) {
            this.title = Objects.requireNonNull(title, "title");
        }

        public Builder externalTitle(Component value) { externalTitle = value; return this; }

        public Builder body(Component content, int width) {
            body.add(new DialogBody.PlainMessage(Objects.requireNonNull(content, "content"), width));
            return this;
        }

        public Builder columns(int value) { columns = value; return this; }
        public Builder buttonWidth(int value) { buttonWidth = value; return this; }
        public Builder canCloseWithEscape(boolean value) { canCloseWithEscape = value; return this; }
        public Builder pause(boolean value) { pause = value; return this; }
        public Builder afterAction(DialogLayout.AfterAction value) { afterAction = value; return this; }
        public Builder onClose(Consumer<DialogResponse> handler) { closeHandler = handler; return this; }

        public Builder exitButton(Component label, Consumer<DialogResponse> handler) {
            return exitButton(label, Component.empty(), 150, handler);
        }

        public Builder exitButton(Component label, Component tooltip, int width,
                                  Consumer<DialogResponse> handler) {
            exitButton = new ExitButton(Objects.requireNonNull(label, "label"),
                    tooltip == null ? Component.empty() : tooltip, width,
                    Objects.requireNonNull(handler, "handler"));
            return this;
        }

        public ServerLinksForm build() {
            if (columns < 1) throw new IllegalStateException("Server-links columns must be positive");
            if (buttonWidth < 1) throw new IllegalStateException("Server-links button width must be positive");
            return new ServerLinksForm(this);
        }
    }
}
