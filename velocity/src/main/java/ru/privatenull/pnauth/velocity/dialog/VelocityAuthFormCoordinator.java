package ru.privatenull.pnauth.velocity.dialog;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.command.AuthCommandRequest;
import ru.privatenull.pnauth.command.AuthCommandService;
import ru.privatenull.pnauth.config.FeatureSettings;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.dialog.AuthDialogFormFactory;
import ru.privatenull.pnauth.dialog.DialogForm;
import ru.privatenull.pnauth.dialog.DialogHandle;
import ru.privatenull.pnauth.dialog.PlayerDialogs;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.platform.PnPlatform;
import ru.privatenull.pnauth.platform.PnPlayer;
import ru.privatenull.pnauth.security.ClickCaptchaService;
import ru.privatenull.pnauth.velocity.VelocityMessages;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Velocity lifecycle adapter for the shared high-level authentication form. */
public final class VelocityAuthFormCoordinator implements AutoCloseable {
    private final ProxyServer proxy;
    private final AuthApi auth;
    private final AuthCommandService commands;
    private final AuthMessages messages;
    private final FeatureSettings features;
    private final MessageFormat format;
    private final int maxPasswordLength;
    private final ProxySettings proxySettings;
    private final PnPlatform platform;
    private final PlayerDialogs dialogs;
    private final ClickCaptchaService captcha;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Submission> submissions = new ConcurrentHashMap<>();
    private final Map<UUID, DialogHandle> activeDialogs = new ConcurrentHashMap<>();

    public VelocityAuthFormCoordinator(ProxyServer proxy, AuthApi auth, AuthCommandService commands,
                                       AuthMessages messages, FeatureSettings features, MessageFormat format,
                                       int maxPasswordLength, ProxySettings proxySettings, PnPlatform platform) {
        this.proxy = proxy;
        this.auth = auth;
        this.commands = commands;
        this.messages = messages;
        this.features = features;
        this.format = format;
        this.maxPasswordLength = maxPasswordLength;
        this.proxySettings = proxySettings;
        this.platform = platform;
        this.dialogs = platform.dialogs();
        this.captcha = new ClickCaptchaService(features.captcha());
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("_pnauthui").build(), new UiCommand());
    }

    public boolean show(Player player, AuthStatus status) {
        UUID playerId = player.getUniqueId();
        Session session = sessions.computeIfAbsent(playerId, ignored -> new Session());
        Submission running = submissions.get(playerId);
        if (running != null && running.session == session) return true;
        clear(player);
        if (status != AuthStatus.UNREGISTERED && status != AuthStatus.UNAUTHENTICATED) return false;
        if (!isOnAuthServer(player)) return false;
        PnPlayer pnPlayer = platform.player(playerId).orElse(null);
        boolean supported = pnPlayer != null && dialogs.supported(pnPlayer);
        if (!auth.shouldUseDialog(playerId, player.getProtocolVersion().getProtocol(), supported)) return false;
        if (!captcha.verified(playerId)) {
            sendCaptcha(player);
            return true;
        }
        open(player, status, null, session);
        return true;
    }

    public void clear(Player player) {
        DialogHandle handle = activeDialogs.remove(player.getUniqueId());
        if (handle != null) handle.close();
    }

    public void clearSession(Player player) {
        UUID playerId = player.getUniqueId();
        Session removed = sessions.remove(playerId);
        Submission submission = submissions.get(playerId);
        if (submission != null && submission.session == removed) submissions.remove(playerId, submission);
        captcha.clear(playerId);
        clear(player);
    }

    public boolean available() { return true; }
    public boolean allowAuthenticationCommand(Player player) { return !requiresCaptcha(player); }
    public void requestCaptcha(Player player) { if (requiresCaptcha(player)) sendCaptcha(player); }

    @Override public void close() {
        activeDialogs.values().forEach(DialogHandle::close);
        activeDialogs.clear();
        submissions.clear();
        sessions.clear();
        captcha.clearAll();
        proxy.getCommandManager().unregister("_pnauthui");
    }

    private void open(Player player, AuthStatus status, Component notice, Session session) {
        boolean register = status == AuthStatus.UNREGISTERED;
        String command = register ? "register" : "login";
        AuthDialogFormFactory.Content content = new AuthDialogFormFactory.Content(
                component(register ? "dialog.register.title" : "dialog.login.title"),
                component(register ? "dialog.register.description" : "dialog.login.description"), notice,
                component(register ? "dialog.register.password" : "dialog.login.password"),
                register ? component("dialog.register.repeat") : null,
                component(register ? "dialog.register.button" : "dialog.login.button"));
        DialogForm form = AuthDialogFormFactory.create(
                register ? AuthDialogFormFactory.Mode.REGISTER : AuthDialogFormFactory.Mode.LOGIN,
                features.repeatPasswordWhenRegister(), maxPasswordLength, content,
                credentials -> submit(player, command, credentials, session),
                () -> closeWithError(player, messages.text("operation-error")));
        PnPlayer pnPlayer = platform.player(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException("Player left before the dialog was shown"));
        DialogHandle handle = dialogs.show(pnPlayer, form);
        DialogHandle previous = activeDialogs.put(player.getUniqueId(), handle);
        if (previous != null) previous.close();
    }

    private void submit(Player player, String command, AuthDialogFormFactory.Credentials credentials,
                        Session session) {
        UUID playerId = player.getUniqueId();
        activeDialogs.remove(playerId);
        if (sessions.get(playerId) != session || !isOnAuthServer(player)
                || auth.isAuthenticated(playerId) || !captcha.verified(playerId)) return;
        Submission submission = new Submission(session);
        if (submissions.putIfAbsent(playerId, submission) != null) return;
        AuthStatus current = auth.status(playerId);
        if ((command.equals("register") && current != AuthStatus.UNREGISTERED)
                || (command.equals("login") && current != AuthStatus.UNAUTHENTICATED)) {
            submissions.remove(playerId, submission);
            showNotice(player, messages.prompt(current));
            return;
        }
        List<String> arguments = command.equals("register")
                ? List.of(credentials.password(), credentials.confirmation()) : List.of(credentials.password());
        commands.execute(new AuthCommandRequest(playerId, player.getUsername(), command, arguments,
                player::hasPermission)).whenComplete((output, error) -> finish(
                        player, session, submission, output, error));
    }

    private void finish(Player player, Session session, Submission submission,
                        List<String> output, Throwable error) {
        UUID playerId = player.getUniqueId();
        if (sessions.get(playerId) != session || submissions.get(playerId) != submission) return;
        try {
            if (error != null) {
                closeWithError(player, messages.text("operation-error"));
            } else if (auth.isAuthenticated(playerId)) {
                clear(player);
                player.sendMessage(VelocityMessages.component(messages.text("auth.success"), format));
            } else {
                String notice = output == null || output.isEmpty()
                        ? messages.prompt(auth.status(playerId)) : output.get(0);
                AuthStatus next = auth.status(playerId);
                if (next == AuthStatus.UNREGISTERED || next == AuthStatus.UNAUTHENTICATED) {
                    closeWithError(player, notice);
                } else {
                    clear(player);
                    player.sendMessage(VelocityMessages.component(notice, format));
                }
            }
        } finally {
            submissions.remove(playerId, submission);
        }
    }

    private void showNotice(Player player, String notice) {
        AuthStatus status = auth.status(player.getUniqueId());
        if (status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED) {
            open(player, status, VelocityMessages.component(notice, format),
                    sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session()));
        }
    }

    private void closeWithError(Player player, String error) {
        clear(player);
        player.sendMessage(VelocityMessages.component(messages.text("dialog.error", Map.of("error", error)), format)
                .append(Component.space()).append(VelocityMessages.component(messages.text("dialog.retry"), format)
                        .clickEvent(ClickEvent.runCommand("/_pnauthui open"))
                        .hoverEvent(HoverEvent.showText(VelocityMessages.component(
                                messages.text("dialog.retry_hover"), format)))));
    }

    private Component component(String key) { return VelocityMessages.component(messages.text(key), format); }

    private void sendCaptcha(Player player) {
        ClickCaptchaService.Challenge challenge = captcha.issue(player.getUniqueId());
        player.sendMessage(VelocityMessages.component(messages.text(
                "captcha.prompt", Map.of("answer", challenge.answer())), format));
        Component options = Component.empty();
        for (ClickCaptchaService.Option option : challenge.options()) {
            options = options.append(VelocityMessages.component(messages.text(
                            "captcha.option", Map.of("value", option.label())), format)
                    .clickEvent(ClickEvent.runCommand("/_pnauthui captcha " + option.token()))
                    .hoverEvent(HoverEvent.showText(VelocityMessages.component(messages.text("captcha.hover"), format))))
                    .append(Component.space());
        }
        player.sendMessage(options);
    }

    private void handleUi(Player player, String[] arguments) {
        if (!isOnAuthServer(player)) return;
        if (arguments.length == 1 && arguments[0].equalsIgnoreCase("open")) {
            show(player, auth.status(player.getUniqueId()));
            return;
        }
        if (arguments.length != 2 || !arguments[0].equalsIgnoreCase("captcha")) return;
        ClickCaptchaService.Result result = captcha.verify(player.getUniqueId(), arguments[1]);
        if (result == ClickCaptchaService.Result.SUCCESS) {
            player.sendMessage(VelocityMessages.component(messages.text("captcha.success"), format));
            show(player, auth.status(player.getUniqueId()));
        } else {
            String key = result == ClickCaptchaService.Result.INVALID ? "captcha.invalid"
                    : result == ClickCaptchaService.Result.EXPIRED ? "captcha.expired" : "captcha.locked";
            player.sendMessage(VelocityMessages.component(messages.text(key), format));
        }
    }

    private boolean requiresCaptcha(Player player) {
        if (!isOnAuthServer(player) || captcha.verified(player.getUniqueId())
                || auth.isAuthenticated(player.getUniqueId())) return false;
        AuthStatus status = auth.status(player.getUniqueId());
        return status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED;
    }

    private boolean isOnAuthServer(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()
                .equalsIgnoreCase(proxySettings.authServer())).orElse(false);
    }

    private final class UiCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            if (invocation.source() instanceof Player player && !auth.isAuthenticated(player.getUniqueId())) {
                handleUi(player, invocation.arguments());
            }
        }
    }

    private static final class Session { }
    private record Submission(Session session) { }
}
