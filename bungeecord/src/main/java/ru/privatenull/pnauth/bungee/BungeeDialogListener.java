package ru.privatenull.pnauth.bungee;

import com.google.gson.JsonObject;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.dialog.Dialog;
import net.md_5.bungee.api.dialog.DialogBase;
import net.md_5.bungee.api.dialog.MultiActionDialog;
import net.md_5.bungee.api.dialog.action.ActionButton;
import net.md_5.bungee.api.dialog.action.CustomClickAction;
import net.md_5.bungee.api.dialog.body.PlainMessageBody;
import net.md_5.bungee.api.dialog.input.DialogInput;
import net.md_5.bungee.api.dialog.input.TextInput;
import net.md_5.bungee.api.event.CustomClickEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.command.AuthCommandRequest;
import ru.privatenull.pnauth.command.AuthCommandService;
import ru.privatenull.pnauth.config.FeatureSettings;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.message.AuthMessages;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class BungeeDialogListener implements Listener {
    private final AuthApi auth;
    private final Plugin plugin;
    private final AuthCommandService commands;
    private final AuthMessages messages;
    private final FeatureSettings settings;
    private final ProxySettings proxySettings;
    private final Map<UUID, ScheduledTask> pending = new ConcurrentHashMap<>();

    BungeeDialogListener(
            Plugin plugin,
            AuthApi auth,
            AuthCommandService commands,
            AuthMessages messages,
            FeatureSettings settings,
            ProxySettings proxySettings
    ) {
        this.plugin = plugin;
        this.auth = auth;
        this.commands = commands;
        this.messages = messages;
        this.settings = settings;
        this.proxySettings = proxySettings;
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (!event.getServer().getInfo().getName().equalsIgnoreCase(proxySettings.authServer())
                || auth.isAuthenticated(player.getUniqueId())) {
            return;
        }
        scheduleWhenLoaded(player);
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        scheduleWhenLoaded(event.getPlayer());
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCustomClick(CustomClickEvent event) {
        ProxiedPlayer player = event.getPlayer();
        JsonObject data = event.getData().getAsJsonObject();
        if (event.getId().endsWith("pnauth_login")) {
            execute(player, "login", List.of(value(data, "password")));
        } else if (event.getId().endsWith("pnauth_register")) {
            String password = value(data, "password");
            String repeat = settings.repeatPasswordWhenRegister() ? value(data, "repeatPassword") : password;
            execute(player, "register", List.of(password, repeat));
        }
    }

    private void execute(ProxiedPlayer player, String command, List<String> args) {
        player.clearDialog();
        commands.execute(new AuthCommandRequest(
                player.getUniqueId(), player.getName(), command, args, player::hasPermission
        )).thenAccept(result -> {
            result.forEach(message -> player.sendMessage(
                    BungeeMessages.components(message, this.messages.format())));
            if (player.isConnected() && !auth.isAuthenticated(player.getUniqueId())) {
                scheduleWhenLoaded(player);
            }
        });
    }

    private void show(ProxiedPlayer player, boolean register) {
        List<DialogInput> inputs = register
                ? settings.repeatPasswordWhenRegister()
                ? List.of(passwordInput("password", messages.text("dialog.register.password")),
                passwordInput("repeatPassword", messages.text("dialog.register.repeat")))
                : List.of(passwordInput("password", messages.text("dialog.register.password")))
                : List.of(passwordInput("password", messages.text("dialog.login.password")));
        String title = register ? messages.text("dialog.register.title") : messages.text("dialog.login.title");
        String button = register ? messages.text("dialog.register.button") : messages.text("dialog.login.button");
        String description = register ? messages.text("dialog.register.description") : messages.text("dialog.login.description");
        String action = register ? "pnauth_register" : "pnauth_login";
        DialogBase base = new DialogBase(BungeeMessages.component(title, messages.format()))
                .body(List.of(new PlainMessageBody(BungeeMessages.component(description, messages.format()), width(400))))
                .inputs(inputs)
                .canCloseWithEscape(false)
                .pause(false)
                .afterAction(DialogBase.AfterAction.CLOSE);
        ActionButton submit = new ActionButton(
                BungeeMessages.component(button, messages.format()), new CustomClickAction(action)
        ).width(width(200));
        Dialog dialog = new MultiActionDialog(base, List.of(submit), 1, null);
        player.showDialog(dialog);
    }

    private TextInput passwordInput(String key, String label) {
        return new TextInput(key, BungeeMessages.component(label, messages.format()))
                .width(width(200))
                .labelVisible(true)
                .maxLength(64);
    }

    private static String value(JsonObject data, String key) {
        return data.has(key) ? data.get(key).getAsString().trim() : "";
    }

    private static int width(int width) {
        return Math.max(1, Math.min(1024, width));
    }

    private void scheduleWhenLoaded(ProxiedPlayer player) {
        cancel(player.getUniqueId());
        long deadline = System.currentTimeMillis() + 30_000L;
        ScheduledTask task = plugin.getProxy().getScheduler().schedule(plugin, () -> {
            if (!player.isConnected()) {
                cancel(player.getUniqueId());
                return;
            }
            AuthStatus status = auth.status(player.getUniqueId());
            if (status == AuthStatus.NOT_LOADED || player.getServer() == null
                    || !player.getServer().getInfo().getName().equalsIgnoreCase(proxySettings.authServer())) {
                if (System.currentTimeMillis() >= deadline) cancel(player.getUniqueId());
                return;
            }
            cancel(player.getUniqueId());
            if (status == AuthStatus.AUTHENTICATED) return;
            int protocol = player.getPendingConnection().getVersion();
            if (status != AuthStatus.UNREGISTERED && status != AuthStatus.UNAUTHENTICATED
                    || !auth.shouldUseDialog(player.getUniqueId(), protocol, true)) {
                if (auth.shouldUseCommandFallback(player.getUniqueId(), protocol, true)) {
                    player.sendMessage(BungeeMessages.components(messages.prompt(status), messages.format()));
                }
                return;
            }
            show(player, status == AuthStatus.UNREGISTERED);
        }, 100, 100, TimeUnit.MILLISECONDS);
        pending.put(player.getUniqueId(), task);
    }

    private void cancel(UUID uniqueId) {
        ScheduledTask task = pending.remove(uniqueId);
        if (task != null) task.cancel();
    }

    public void close() {
        pending.keySet().forEach(this::cancel);
    }
}
