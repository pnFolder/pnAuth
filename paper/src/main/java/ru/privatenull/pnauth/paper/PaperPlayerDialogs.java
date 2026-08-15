package ru.privatenull.pnauth.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import ru.privatenull.pnauth.dialog.DialogAction;
import ru.privatenull.pnauth.dialog.DialogBody;
import ru.privatenull.pnauth.dialog.DialogButton;
import ru.privatenull.pnauth.dialog.DialogHandle;
import ru.privatenull.pnauth.dialog.DialogInput;
import ru.privatenull.pnauth.dialog.DialogResponse;
import ru.privatenull.pnauth.dialog.DialogSubscription;
import ru.privatenull.pnauth.dialog.DialogType;
import ru.privatenull.pnauth.dialog.PlayerDialog;
import ru.privatenull.pnauth.dialog.PlayerDialogs;
import ru.privatenull.pnauth.platform.PnPlayer;
import ru.privatenull.pnauth.platform.PlayerResourceKey;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Runtime adapter for the vanilla dialog protocol available since Minecraft 1.21.6. */
public final class PaperPlayerDialogs implements PlayerDialogs, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Plugin plugin;
    private final boolean nativeDialogsAvailable;
    private final ConcurrentMap<PlayerResourceKey, NativeHandle> handles = new ConcurrentHashMap<>();
    private final ConcurrentMap<PlayerResourceKey, NativeHandle> actions = new ConcurrentHashMap<>();

    public PaperPlayerDialogs(Plugin plugin) {
        this.plugin = plugin;
        this.nativeDialogsAvailable = supportsNativeDialogs();
        if (nativeDialogsAvailable) registerResponseListener();
    }

    @Override
    public boolean supported(PnPlayer player) {
        return nativeDialogsAvailable && player.connected();
    }

    @Override
    public DialogHandle show(PnPlayer player, PlayerDialog dialog) {
        if (!supported(player)) {
            throw new UnsupportedOperationException("Native dialogs with responses require Paper 1.21.7 or newer");
        }
        PlayerResourceKey key = new PlayerResourceKey(player.uniqueId(), dialog.id());
        NativeHandle handle = handles.compute(key, (ignored, current) -> {
            if (current == null || !current.active()) return new NativeHandle(key, dialog);
            current.replace(dialog);
            return current;
        });
        return handle;
    }

    @Override public Optional<DialogHandle> find(UUID playerId, String dialogId) {
        return Optional.ofNullable(handles.get(new PlayerResourceKey(playerId, dialogId)));
    }

    @Override public boolean close(UUID playerId, String dialogId) {
        NativeHandle handle = handles.get(new PlayerResourceKey(playerId, dialogId));
        if (handle == null) return false;
        handle.close();
        return true;
    }

    @Override public void closeAll(UUID playerId) {
        handles.entrySet().stream().filter(entry -> entry.getKey().playerId().equals(playerId))
                .map(Map.Entry::getValue).toList().forEach(NativeHandle::close);
    }

    @Override public void close() {
        handles.values().forEach(NativeHandle::close);
        handles.clear();
        actions.clear();
    }

    private final class NativeHandle implements DialogHandle {
        private final PlayerResourceKey key;
        private final CompletableFuture<DialogResponse> response = new CompletableFuture<>();
        private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<DialogResponse>> listeners =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile PlayerDialog dialog;
        private volatile boolean active = true;

        private NativeHandle(PlayerResourceKey key, PlayerDialog dialog) {
            this.key = key;
            replace(dialog);
        }

        @Override public UUID playerId() { return key.playerId(); }
        @Override public String dialogId() { return key.resourceId(); }
        @Override public boolean active() { return active; }
        @Override public CompletionStage<DialogResponse> response() { return response; }
        @Override public DialogSubscription onResponse(java.util.function.Consumer<DialogResponse> listener) {
            listeners.add(java.util.Objects.requireNonNull(listener, "listener"));
            return () -> listeners.remove(listener);
        }

        @Override
        public synchronized void replace(PlayerDialog replacement) {
            if (!active) throw new IllegalStateException("Dialog handle is closed");
            unregisterActions();
            dialog = replacement;
            registerActions(replacement);
            execute("dialog show " + key.playerId() + " " + serialize(replacement));
        }

        @Override
        public synchronized void close() {
            if (!active) return;
            active = false;
            handles.remove(key, this);
            unregisterActions();
            execute("dialog clear " + key.playerId());
            publish(new DialogResponse("", Map.of(), true));
        }

        private void accept(String action, Map<String, Object> values) {
            if (!active) return;
            publish(new DialogResponse(action, values, false));
            if (dialog.layout().afterAction() != ru.privatenull.pnauth.dialog.DialogLayout.AfterAction.NONE) {
                active = false;
                handles.remove(key, this);
                unregisterActions();
            }
        }

        private void publish(DialogResponse value) {
            response.complete(value);
            listeners.forEach(listener -> listener.accept(value));
        }

        private void registerActions(PlayerDialog value) {
            buttons(value.type()).forEach(button -> {
                if (button != null && button.action() instanceof DialogAction.DynamicCustom custom) {
                    actions.put(new PlayerResourceKey(key.playerId(), custom.id()), this);
                }
            });
        }

        private void unregisterActions() {
            actions.entrySet().removeIf(entry -> entry.getValue() == this);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerResponseListener() {
        try {
            Class<? extends Event> eventType = (Class<? extends Event>) Class.forName(
                    "io.papermc.paper.event.player.PlayerCustomClickEvent");
            Listener listener = new Listener() { };
            Bukkit.getPluginManager().registerEvent(eventType, listener, EventPriority.NORMAL,
                    (ignored, event) -> receiveResponse(event), plugin, true);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Paper exposes dialogs but PlayerCustomClickEvent is unavailable", exception);
        }
    }

    private void receiveResponse(Event event) {
        try {
            String identifier = event.getClass().getMethod("getIdentifier").invoke(event).toString();
            Object connection = event.getClass().getMethod("getCommonConnection").invoke(event);
            Method getPlayer = connection.getClass().getMethod("getPlayer");
            Object player = getPlayer.invoke(connection);
            UUID playerId = (UUID) player.getClass().getMethod("getUniqueId").invoke(player);
            NativeHandle handle = actions.get(new PlayerResourceKey(playerId, identifier));
            if (handle == null) return;
            Object view = event.getClass().getMethod("getDialogResponseView").invoke(event);
            handle.accept(identifier, readValues(view, handle.dialog));
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Could not decode a native dialog response: " + exception.getMessage());
        }
    }

    private Map<String, Object> readValues(Object view, PlayerDialog dialog) throws ReflectiveOperationException {
        Map<String, Object> values = new LinkedHashMap<>();
        if (view == null) return values;
        for (DialogInput input : dialog.layout().inputs()) {
            String method = input instanceof DialogInput.Text ? "getText"
                    : input instanceof DialogInput.Toggle ? "getBoolean"
                    : input instanceof DialogInput.NumberRange ? "getFloat" : "getText";
            values.put(input.id(), view.getClass().getMethod(method, String.class).invoke(view, input.id()));
        }
        return values;
    }

    private void execute(String command) {
        Bukkit.getGlobalRegionScheduler().execute(plugin,
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private String serialize(PlayerDialog dialog) {
        try {
            return JSON.writeValueAsString(toDocument(dialog));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Dialog cannot be serialized", exception);
        }
    }

    private Map<String, Object> toDocument(PlayerDialog dialog) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", component(dialog.layout().title()));
        root.put("external_title", component(dialog.layout().externalTitle()));
        root.put("body", bodies(dialog.layout().body()));
        root.put("inputs", inputs(dialog.layout().inputs()));
        root.put("can_close_with_escape", dialog.layout().canCloseWithEscape());
        root.put("pause", dialog.layout().pause());
        root.put("after_action", lower(dialog.layout().afterAction().name()));
        applyType(root, dialog.type());
        return root;
    }

    private void applyType(Map<String, Object> root, DialogType type) throws Exception {
        if (type instanceof DialogType.Notice notice) {
            root.put("type", "minecraft:notice"); root.put("action", button(notice.action()));
        } else if (type instanceof DialogType.Confirmation confirmation) {
            root.put("type", "minecraft:confirmation");
            root.put("yes", button(confirmation.yes())); root.put("no", button(confirmation.no()));
        } else if (type instanceof DialogType.MultiAction multi) {
            root.put("type", "minecraft:multi_action"); root.put("actions", buttonList(multi.actions()));
            optional(root, "exit_action", multi.exitAction() == null ? null : button(multi.exitAction()));
            root.put("columns", multi.columns());
        } else if (type instanceof DialogType.ServerLinks links) {
            root.put("type", "minecraft:server_links"); root.put("columns", links.columns());
            root.put("button_width", links.buttonWidth());
            optional(root, "exit_action", links.exitAction() == null ? null : button(links.exitAction()));
        } else if (type instanceof DialogType.DialogList list) {
            root.put("type", "minecraft:dialog_list"); root.put("columns", list.columns());
            root.put("button_width", list.buttonWidth());
            root.put("dialogs", list.dialogTag() == null
                    ? list.dialogs().stream().map(dialog -> {
                        try { return toDocument(dialog); }
                        catch (Exception exception) { throw new IllegalArgumentException(exception); }
                    }).toList()
                    : "#" + list.dialogTag());
            optional(root, "exit_action", list.exitAction() == null ? null : button(list.exitAction()));
        }
    }

    private List<Map<String, Object>> bodies(List<DialogBody> source) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DialogBody body : source) {
            Map<String, Object> value = new LinkedHashMap<>();
            if (body instanceof DialogBody.PlainMessage message) {
                value.put("type", "minecraft:plain_message"); value.put("contents", component(message.content()));
                value.put("width", message.width());
            } else if (body instanceof DialogBody.Item item) {
                value.put("type", "minecraft:item"); value.put("item", item.itemStack());
                if (item.description() != null) value.put("description", Map.of(
                        "contents", component(item.description()), "width", item.descriptionWidth()));
                value.put("show_decorations", item.showDecorations()); value.put("show_tooltip", item.showTooltip());
                value.put("width", item.width()); value.put("height", item.height());
            }
            result.add(value);
        }
        return result;
    }

    private List<Map<String, Object>> inputs(List<DialogInput> source) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DialogInput input : source) {
            Map<String, Object> value = new LinkedHashMap<>(); value.put("key", input.id());
            value.put("label", component(input.label()));
            if (input instanceof DialogInput.Text text) {
                value.put("type", "minecraft:text"); value.put("label_visible", text.labelVisible());
                value.put("initial", text.initialValue()); value.put("max_length", text.maximumLength());
                value.put("width", text.width());
                if (text.multiline() != null) {
                    Map<String, Object> multiline = new LinkedHashMap<>();
                    optional(multiline, "max_lines", text.multiline().maximumLines());
                    optional(multiline, "height", text.multiline().height()); value.put("multiline", multiline);
                }
            } else if (input instanceof DialogInput.Toggle toggle) {
                value.put("type", "minecraft:boolean"); value.put("initial", toggle.initialValue());
                value.put("on_true", toggle.onTrue()); value.put("on_false", toggle.onFalse());
            } else if (input instanceof DialogInput.Choice choice) {
                value.put("type", "minecraft:single_option"); value.put("label_visible", choice.labelVisible());
                value.put("width", choice.width());
                List<Map<String, Object>> options = new ArrayList<>();
                for (DialogInput.Choice.Option option : choice.options()) options.add(Map.of(
                        "id", option.id(), "display", component(option.display()), "initial", option.initial()));
                value.put("options", options);
            } else if (input instanceof DialogInput.NumberRange range) {
                value.put("type", "minecraft:number_range"); value.put("label_format", range.labelFormat());
                value.put("width", range.width()); value.put("start", range.start()); value.put("end", range.end());
                optional(value, "initial", range.initial()); optional(value, "step", range.step());
            }
            result.add(value);
        }
        return result;
    }

    private Map<String, Object> button(DialogButton button) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("label", component(button.label()));
        if (button.tooltip() != null) value.put("tooltip", component(button.tooltip()));
        value.put("width", button.width());
        if (!(button.action() instanceof DialogAction.None)) value.put("action", action(button.action()));
        return value;
    }

    private List<Map<String, Object>> buttonList(List<DialogButton> buttons) throws Exception {
        List<Map<String, Object>> values = new ArrayList<>();
        for (DialogButton button : buttons) values.add(button(button));
        return values;
    }

    private Map<String, Object> action(DialogAction action) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (action instanceof DialogAction.Static fixed) {
            value.put("type", fixed.type()); value.putAll(fixed.payload());
        } else if (action instanceof DialogAction.DynamicRunCommand command) {
            value.put("type", "minecraft:dynamic/run_command"); value.put("template", command.template());
        } else if (action instanceof DialogAction.DynamicCustom custom) {
            value.put("type", "minecraft:dynamic/custom"); value.put("id", custom.id());
            if (!custom.additions().isEmpty()) value.put("additions", custom.additions());
        }
        return value;
    }

    private Object component(net.kyori.adventure.text.Component component) throws Exception {
        return JSON.readTree(GsonComponentSerializer.gson().serialize(component));
    }

    private List<DialogButton> buttons(DialogType type) {
        if (type instanceof DialogType.Notice value) return List.of(value.action());
        if (type instanceof DialogType.Confirmation value) return List.of(value.yes(), value.no());
        if (type instanceof DialogType.MultiAction value) {
            List<DialogButton> result = new ArrayList<>(value.actions());
            if (value.exitAction() != null) result.add(value.exitAction());
            return result;
        }
        if (type instanceof DialogType.ServerLinks value && value.exitAction() != null) return List.of(value.exitAction());
        if (type instanceof DialogType.DialogList value && value.exitAction() != null) return List.of(value.exitAction());
        return List.of();
    }

    private boolean supportsNativeDialogs() {
        String[] parts = Bukkit.getMinecraftVersion().split("\\.");
        if (parts.length < 2) return false;
        int minor = Integer.parseInt(parts[1]);
        int patch = parts.length > 2 ? Integer.parseInt(parts[2].replaceAll("\\D.*", "")) : 0;
        return minor > 21 || minor == 21 && patch >= 7;
    }

    private static String lower(String value) { return value.toLowerCase(java.util.Locale.ROOT); }
    private static void optional(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
