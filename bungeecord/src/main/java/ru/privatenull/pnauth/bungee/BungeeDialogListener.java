package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.command.AuthCommandRequest;
import ru.privatenull.pnauth.command.AuthCommandService;
import ru.privatenull.pnauth.config.FeatureSettings;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.security.ClickCaptchaService;
import ru.privatenull.pnauth.dialog.AuthDialogFormFactory;
import ru.privatenull.pnauth.dialog.DialogForm;
import ru.privatenull.pnauth.dialog.DialogHandle;
import ru.privatenull.pnauth.dialog.PlayerDialogs;
import ru.privatenull.pnauth.platform.PnPlatform;

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
    private final int maxPasswordLength;
    private final ProxySettings proxySettings;
    private final Map<UUID, ScheduledTask> pending = new ConcurrentHashMap<>();
    /**
     * A dialog response is only trusted when it matches an action that this proxy instance issued
     * to this exact player. Custom click action packets are client-controlled, so their static IDs
     * alone must never authorize a login or registration attempt.
     */
    private final Map<UUID, DialogHandle> activeDialogs = new ConcurrentHashMap<>();
    private final PnPlatform platform;
    private final PlayerDialogs dialogs;
    private final ClickCaptchaService captcha;
    private final Command uiCommand;

    BungeeDialogListener(
            Plugin plugin,
            AuthApi auth,
            AuthCommandService commands,
            AuthMessages messages,
            FeatureSettings settings,
            int maxPasswordLength,
            ProxySettings proxySettings,
            PnPlatform platform
    ) {
        this.plugin = plugin;
        this.auth = auth;
        this.commands = commands;
        this.messages = messages;
        this.settings = settings;
        this.maxPasswordLength = maxPasswordLength;
        this.proxySettings = proxySettings;
        this.platform = platform;
        this.dialogs = platform.dialogs();
        this.captcha = new ClickCaptchaService(settings.captcha());
        this.uiCommand = new UiCommand();
        plugin.getProxy().getPluginManager().registerCommand(plugin, uiCommand);
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (!event.getServer().getInfo().getName().equalsIgnoreCase(proxySettings.authServer())
                || auth.isAuthenticated(player.getUniqueId())) {
            clearNativeDialog(player.getUniqueId());
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
        captcha.clear(event.getPlayer().getUniqueId());
        clearNativeDialog(event.getPlayer().getUniqueId());
    }

    private void execute(ProxiedPlayer player, String command, List<String> args) {
        clearNativeDialog(player.getUniqueId());
        commands.execute(new AuthCommandRequest(
                player.getUniqueId(), player.getName(), command, args, player::hasPermission
        )).thenAccept(result -> {
            if (auth.isAuthenticated(player.getUniqueId())) {
                player.sendMessage(BungeeMessages.components(messages.text("auth.success"), messages.format()));
            } else if (player.isConnected()) {
                String error = result == null || result.isEmpty() ? messages.text("operation-error") : result.get(0);
                sendDialogError(player, error);
            }
        });
    }

    private void show(ProxiedPlayer player, boolean register) {
        String command = register ? "register" : "login";
        var pnPlayer = platform.player(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException("Player left before the dialog was shown"));
        AuthDialogFormFactory.Content content = new AuthDialogFormFactory.Content(
                dialogComponent(register ? "dialog.register.title" : "dialog.login.title"),
                dialogComponent(register ? "dialog.register.description" : "dialog.login.description"),
                null,
                dialogComponent(register ? "dialog.register.password" : "dialog.login.password"),
                register ? dialogComponent("dialog.register.repeat") : null,
                dialogComponent(register ? "dialog.register.button" : "dialog.login.button"));
        DialogForm form = AuthDialogFormFactory.create(
                register ? AuthDialogFormFactory.Mode.REGISTER : AuthDialogFormFactory.Mode.LOGIN,
                settings.repeatPasswordWhenRegister(), maxPasswordLength, content,
                credentials -> {
                    activeDialogs.remove(player.getUniqueId());
                    if (!isOnAuthServer(player) || auth.isAuthenticated(player.getUniqueId())) return;
                    if (!captcha.verified(player.getUniqueId())) {
                        sendCaptcha(player);
                        return;
                    }
                    execute(player, command, command.equals("login")
                            ? List.of(credentials.password())
                            : List.of(credentials.password(), credentials.confirmation()));
                },
                () -> sendDialogError(player, messages.text("operation-error")));
        DialogHandle handle = dialogs.show(pnPlayer, form);
        DialogHandle previous = activeDialogs.put(player.getUniqueId(), handle);
        if (previous != null) previous.close();
    }

    private net.kyori.adventure.text.Component dialogComponent(String key) {
        return BungeeMessages.adventureComponent(messages.text(key), messages.format());
    }

    /*
     * Legacy implementation retained for rollback reference:
     * the adapter previously assembled DialogLayout, DialogInput, DialogButton, a random
     * DynamicCustom action and its response parser here. AuthDialogFormFactory now owns that
     * complete schema and callback routing for every platform.
     */

    private void scheduleWhenLoaded(ProxiedPlayer player) {
        cancel(player.getUniqueId());
        clearNativeDialog(player.getUniqueId());
        long deadline = System.currentTimeMillis() + 30_000L;
        ScheduledTask task = plugin.getProxy().getScheduler().schedule(plugin, () -> {
            if (!player.isConnected()) {
                cancel(player.getUniqueId());
                return;
            }
            AuthStatus status = auth.status(player.getUniqueId());
            if (status == AuthStatus.NOT_LOADED || player.getServer() == null
                    || !player.getServer().getInfo().getName().equalsIgnoreCase(proxySettings.authServer())) {
                if (System.currentTimeMillis() >= deadline) {
                    cancel(player.getUniqueId());
                    clearNativeDialog(player.getUniqueId());
                }
                return;
            }
            cancel(player.getUniqueId());
            if (status == AuthStatus.AUTHENTICATED) return;
            int protocol = player.getPendingConnection().getVersion();
            boolean dialogStatus = status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED;
            boolean shouldShowDialog = dialogStatus && auth.shouldUseDialog(player.getUniqueId(), protocol, true);
            if (!shouldShowDialog) {
                sendCommandFallback(player, status, protocol, dialogStatus);
                return;
            }
            if (!captcha.verified(player.getUniqueId())) {
                sendCaptcha(player);
                return;
            }
            try {
                show(player, status == AuthStatus.UNREGISTERED);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not show Bungee dialog for " + player.getName()
                        + "; falling back to commands: " + exception.getMessage());
                sendCommandFallback(player, status, protocol, true);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        pending.put(player.getUniqueId(), task);
    }

    private void cancel(UUID uniqueId) {
        ScheduledTask task = pending.remove(uniqueId);
        if (task != null) task.cancel();
    }

    public void close() {
        pending.keySet().forEach(this::cancel);
        activeDialogs.values().forEach(DialogHandle::close);
        activeDialogs.clear();
        captcha.clearAll();
        plugin.getProxy().getPluginManager().unregisterCommand(uiCommand);
    }

    boolean allowAuthenticationCommand(ProxiedPlayer player) {
        return !requiresCaptcha(player);
    }

    void requestCaptcha(ProxiedPlayer player) {
        if (requiresCaptcha(player)) sendCaptcha(player);
    }

    private boolean requiresCaptcha(ProxiedPlayer player) {
        if (!isOnAuthServer(player) || captcha.verified(player.getUniqueId())
                || auth.isAuthenticated(player.getUniqueId())) {
            return false;
        }
        AuthStatus status = auth.status(player.getUniqueId());
        return status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED;
    }

    private void clearNativeDialog(UUID playerId) {
        DialogHandle handle = activeDialogs.remove(playerId);
        if (handle != null) handle.close();
    }

    private void sendCaptcha(ProxiedPlayer player) {
        ClickCaptchaService.Challenge challenge = captcha.issue(player.getUniqueId());
        player.sendMessage(BungeeMessages.components(messages.text(
                "captcha.prompt", Map.of("answer", challenge.answer())), messages.format()));
        java.util.List<BaseComponent> options = new java.util.ArrayList<>();
        for (ClickCaptchaService.Option option : challenge.options()) {
            BaseComponent[] button = BungeeMessages.components(messages.text(
                    "captcha.option", Map.of("value", option.label())), messages.format());
            for (BaseComponent part : button) {
                part.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/_pnauthui captcha " + option.token()));
                options.add(part);
            }
            options.add(new net.md_5.bungee.api.chat.TextComponent(" "));
        }
        player.sendMessage(options.toArray(BaseComponent[]::new));
    }

    private void sendDialogError(ProxiedPlayer player, String error) {
        clearNativeDialog(player.getUniqueId());
        java.util.List<BaseComponent> line = new java.util.ArrayList<>(java.util.List.of(
                BungeeMessages.components(messages.text("dialog.error", Map.of("error", error)), messages.format())));
        line.add(new net.md_5.bungee.api.chat.TextComponent(" "));
        BaseComponent[] retry = BungeeMessages.components(messages.text("dialog.retry"), messages.format());
        for (BaseComponent part : retry) {
            part.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/_pnauthui open"));
            line.add(part);
        }
        player.sendMessage(line.toArray(BaseComponent[]::new));
    }

    private void handleUi(ProxiedPlayer player, String[] args) {
        if (!isOnAuthServer(player)) return;
        if (args.length == 1 && args[0].equalsIgnoreCase("open")) {
            scheduleWhenLoaded(player);
            return;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("captcha")) return;
        ClickCaptchaService.Result result = captcha.verify(player.getUniqueId(), args[1]);
        if (result == ClickCaptchaService.Result.SUCCESS) {
            player.sendMessage(BungeeMessages.components(messages.text("captcha.success"), messages.format()));
            scheduleWhenLoaded(player);
            return;
        }
        String key = switch (result) {
            case INVALID -> "captcha.invalid";
            case EXPIRED -> "captcha.expired";
            case LOCKED -> "captcha.locked";
            default -> "captcha.invalid";
        };
        player.sendMessage(BungeeMessages.components(messages.text(key), messages.format()));
        if (result != ClickCaptchaService.Result.INVALID) sendRetryCaptcha(player);
    }

    private void sendRetryCaptcha(ProxiedPlayer player) {
        BaseComponent[] retry = BungeeMessages.components(messages.text("captcha.retry"), messages.format());
        for (BaseComponent part : retry) {
            part.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/_pnauthui open"));
        }
        player.sendMessage(retry);
    }

    private final class UiCommand extends Command {
        private UiCommand() {
            super("_pnauthui");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!(sender instanceof ProxiedPlayer player)) return;
            if (!auth.isAuthenticated(player.getUniqueId())) handleUi(player, args);
        }
    }

    private boolean isOnAuthServer(ProxiedPlayer player) {
        return player.isConnected() && player.getServer() != null
                && player.getServer().getInfo().getName().equalsIgnoreCase(proxySettings.authServer());
    }

    private void sendCommandFallback(ProxiedPlayer player, AuthStatus status, int protocol, boolean dialogStatus) {
        if (!dialogStatus || auth.shouldUseCommandFallback(player.getUniqueId(), protocol, true)) {
            player.sendMessage(BungeeMessages.components(messages.prompt(status), messages.format()));
        }
    }

}
