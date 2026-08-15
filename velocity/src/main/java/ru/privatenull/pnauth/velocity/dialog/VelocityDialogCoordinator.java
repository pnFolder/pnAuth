package ru.privatenull.pnauth.velocity.dialog;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.slf4j.Logger;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.command.AuthCommandRequest;
import ru.privatenull.pnauth.command.AuthCommandService;
import ru.privatenull.pnauth.config.FeatureSettings;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.velocity.VelocityMessages;
import ru.privatenull.pnauth.security.ClickCaptchaService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @deprecated Legacy pre-DialogForm implementation kept only as nearby rollback reference.
 * Runtime wiring uses {@link VelocityAuthFormCoordinator}.
 */
@Deprecated(forRemoval = false)
public final class VelocityDialogCoordinator implements AutoCloseable {
    private final AuthApi auth;
    private final AuthCommandService commands;
    private final AuthMessages messages;
    private final FeatureSettings features;
    private final MessageFormat format;
    private final int maxPasswordLength;
    private final VelocityDialogService dialogs;
    private final ProxyServer proxy;
    private final ProxySettings proxySettings;
    private final ClickCaptchaService captcha;
    /** Each connection generation receives a distinct marker, so stale callbacks cannot affect a reconnect. */
    private final Map<UUID, DialogSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSubmission> submissions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingDialog> pendingDialogs = new ConcurrentHashMap<>();

    public VelocityDialogCoordinator(ProxyServer proxy, Logger logger, AuthApi auth,
                                     AuthCommandService commands, AuthMessages messages,
                                     FeatureSettings features, MessageFormat format,
                                     int maxPasswordLength, ProxySettings proxySettings) {
        this.auth = auth;
        this.proxy = proxy;
        this.proxySettings = proxySettings;
        this.commands = commands;
        this.messages = messages;
        this.features = features;
        this.format = format;
        this.maxPasswordLength = maxPasswordLength;
        this.dialogs = VelocityDialogServiceFactory.create(proxy, logger, this::submit);
        this.captcha = new ClickCaptchaService(features.captcha());
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("_pnauthui").build(),
                new UiCommand());
    }

    public boolean show(Player player, AuthStatus status) {
        UUID playerId = player.getUniqueId();
        DialogSession session = sessionFor(playerId);
        PendingSubmission activeSubmission = submissions.get(playerId);
        if (activeSubmission != null) {
            if (activeSubmission.session() == session) return true;
            submissions.remove(playerId, activeSubmission);
        }
        clearPendingDialog(playerId);
        if (status != AuthStatus.UNREGISTERED && status != AuthStatus.UNAUTHENTICATED) return false;
        if (!isOnAuthServer(player)) return false;
        int protocol = player.getProtocolVersion().getProtocol();
        if (!auth.shouldUseDialog(player.getUniqueId(), protocol, dialogs.available())) return false;
        if (!captcha.verified(player.getUniqueId())) {
            sendCaptcha(player);
            return true;
        }
        showDialog(player, status, null);
        return true;
    }

    public void clear(Player player) {
        clearPendingDialog(player.getUniqueId());
        clearTransport(player);
    }

    public void clearSession(Player player) {
        UUID playerId = player.getUniqueId();
        DialogSession session = sessions.remove(playerId);
        if (session != null) {
            clearPendingDialog(playerId, session);
            PendingSubmission submission = submissions.get(playerId);
            if (submission != null && submission.session() == session) {
                submissions.remove(playerId, submission);
            }
        }
        captcha.clear(playerId);
        clearTransport(player);
    }

    public boolean available() {
        return dialogs.available();
    }

    public boolean allowAuthenticationCommand(Player player) {
        return !requiresCaptcha(player);
    }

    public void requestCaptcha(Player player) {
        if (requiresCaptcha(player)) sendCaptcha(player);
    }

    @Override
    public void close() {
        submissions.clear();
        sessions.clear();
        pendingDialogs.clear();
        captcha.clearAll();
        proxy.getCommandManager().unregister("_pnauthui");
        dialogs.close();
    }

    private void submit(Player player, String actionId, Map<String, String> values) {
        PendingDialog pending = consumeDialog(player, actionId);
        if (pending == null) return;
        UUID playerId = player.getUniqueId();
        PendingSubmission submission = new PendingSubmission(pending.session());
        PendingSubmission activeSubmission = submissions.putIfAbsent(playerId, submission);
        if (activeSubmission != null) {
            // A user can reopen the UI while a request is running. Keep this one-shot nonce valid
            // instead of silently consuming it behind the active request.
            pendingDialogs.putIfAbsent(playerId, pending);
            return;
        }
        if (!isCurrentSession(playerId, pending.session())) {
            submissions.remove(playerId, submission);
            return;
        }
        String command = pending.command();
        AuthStatus current = auth.status(player.getUniqueId());
        if (current == AuthStatus.AUTHENTICATED) {
            clear(player);
            submissions.remove(playerId, submission);
            return;
        }
        if ((command.equals("register") && current != AuthStatus.UNREGISTERED)
                || (command.equals("login") && current != AuthStatus.UNAUTHENTICATED)) {
            showNotice(player, messages.prompt(current));
            submissions.remove(playerId, submission);
            return;
        }
        String password = values.getOrDefault("password", "");
        List<String> arguments = command.equals("register")
                ? List.of(password, features.repeatPasswordWhenRegister()
                        ? values.getOrDefault("confirmation", "") : password)
                : List.of(password);
        AuthCommandRequest request = new AuthCommandRequest(
                player.getUniqueId(), player.getUsername(), command, arguments, player::hasPermission);
        commands.execute(request).whenComplete((output, error) -> {
            // A disconnect/reconnect replaces the session marker and removes this exact submission.
            // Never let an old completion clear or message the newly connected player.
            if (!isCurrentSession(playerId, pending.session()) || submissions.get(playerId) != submission) return;
            try {
                if (error != null) {
                    closeWithError(player, messages.text("operation-error"));
                    return;
                }
                if (auth.isAuthenticated(playerId)) {
                    clear(player);
                    player.sendMessage(VelocityMessages.component(messages.text("auth.success"), format));
                    return;
                }
                String notice = output == null || output.isEmpty()
                        ? messages.prompt(auth.status(playerId)) : output.get(0);
                AuthStatus status = auth.status(playerId);
                if (status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED) {
                    closeWithError(player, notice);
                } else {
                    clear(player);
                    player.sendMessage(VelocityMessages.component(notice, format));
                }
            } finally {
                submissions.remove(playerId, submission);
            }
        });
    }

    private void closeWithError(Player player, String error) {
        clear(player);
        Component line = VelocityMessages.component(messages.text("dialog.error", Map.of("error", error)), format)
                .append(Component.space())
                .append(VelocityMessages.component(messages.text("dialog.retry"), format)
                        .clickEvent(ClickEvent.runCommand("/_pnauthui open"))
                        .hoverEvent(HoverEvent.showText(VelocityMessages.component(
                                messages.text("dialog.retry_hover"), format))));
        player.sendMessage(line);
    }

    private void sendCaptcha(Player player) {
        ClickCaptchaService.Challenge challenge = captcha.issue(player.getUniqueId());
        player.sendMessage(VelocityMessages.component(messages.text(
                "captcha.prompt", Map.of("answer", challenge.answer())), format));
        Component options = Component.empty();
        for (ClickCaptchaService.Option option : challenge.options()) {
            Component button = VelocityMessages.component(messages.text(
                            "captcha.option", Map.of("value", option.label())), format)
                    .clickEvent(ClickEvent.runCommand("/_pnauthui captcha " + option.token()))
                    .hoverEvent(HoverEvent.showText(VelocityMessages.component(messages.text("captcha.hover"), format)));
            options = options.append(button).append(Component.space());
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
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(VelocityMessages.component(messages.text("captcha.success"), format));
                show(player, auth.status(player.getUniqueId()));
            }
            case INVALID -> player.sendMessage(VelocityMessages.component(messages.text("captcha.invalid"), format));
            case EXPIRED, LOCKED -> {
                String key = result == ClickCaptchaService.Result.EXPIRED ? "captcha.expired" : "captcha.locked";
                player.sendMessage(VelocityMessages.component(messages.text(key), format)
                        .append(Component.space()).append(VelocityMessages.component(messages.text("captcha.retry"), format)
                                .clickEvent(ClickEvent.runCommand("/_pnauthui open"))));
            }
        }
    }

    private final class UiCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) return;
            String[] arguments = invocation.arguments();
            if (!auth.isAuthenticated(player.getUniqueId())) handleUi(player, arguments);
        }
    }

    private void showNotice(Player player, String notice) {
        AuthStatus status = auth.status(player.getUniqueId());
        if (status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED) {
            showDialog(player, status, VelocityMessages.component(notice, format));
        }
    }

    private void showDialog(Player player, AuthStatus status, Component notice) {
        PendingDialog pending = issueDialog(player.getUniqueId(), status == AuthStatus.UNREGISTERED);
        try {
            dialogs.show(player, form(status, notice, pending.actionId()));
        } catch (RuntimeException exception) {
            pendingDialogs.remove(player.getUniqueId(), pending);
            throw exception;
        }
    }

    private VelocityDialogService.DialogForm form(AuthStatus status, Component notice, String actionId) {
        boolean register = status == AuthStatus.UNREGISTERED;
        List<VelocityDialogService.TextField> fields = register && features.repeatPasswordWhenRegister()
                ? List.of(field("password", "dialog.register.password"),
                          field("confirmation", "dialog.register.repeat"))
                : List.of(field("password", register ? "dialog.register.password" : "dialog.login.password"));
        return new VelocityDialogService.DialogForm(
                component(register ? "dialog.register.title" : "dialog.login.title"),
                notice,
                fields,
                component(register ? "dialog.register.button" : "dialog.login.button"),
                actionId);
    }

    private VelocityDialogService.TextField field(String key, String messageKey) {
        return new VelocityDialogService.TextField(key, component(messageKey), maxPasswordLength);
    }

    private Component component(String key) {
        return VelocityMessages.component(messages.text(key), format);
    }

    private PendingDialog issueDialog(UUID uniqueId, boolean register) {
        PendingDialog pending = new PendingDialog(
                "pnauth:" + (register ? "register" : "login") + "-" + UUID.randomUUID().toString().replace("-", ""),
                register ? "register" : "login", sessionFor(uniqueId));
        pendingDialogs.put(uniqueId, pending);
        return pending;
    }

    private PendingDialog consumeDialog(Player player, String actionId) {
        if (!isOnAuthServer(player) || auth.isAuthenticated(player.getUniqueId()) || !captcha.verified(player.getUniqueId())) {
            return null;
        }
        DialogSession session = sessions.get(player.getUniqueId());
        if (session == null) return null;
        PendingDialog pending = pendingDialogs.get(player.getUniqueId());
        if (pending == null || pending.session() != session || !pending.actionId().equals(actionId)) return null;
        return pendingDialogs.remove(player.getUniqueId(), pending) ? pending : null;
    }

    private boolean isOnAuthServer(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()
                .equalsIgnoreCase(proxySettings.authServer())).orElse(false);
    }

    private void clearPendingDialog(UUID uniqueId) {
        pendingDialogs.remove(uniqueId);
    }

    private void clearPendingDialog(UUID uniqueId, DialogSession session) {
        PendingDialog pending = pendingDialogs.get(uniqueId);
        if (pending != null && pending.session() == session) pendingDialogs.remove(uniqueId, pending);
    }

    private void clearTransport(Player player) {
        if (dialogs.available()) dialogs.clear(player);
    }

    private DialogSession sessionFor(UUID uniqueId) {
        return sessions.computeIfAbsent(uniqueId, ignored -> new DialogSession());
    }

    private boolean isCurrentSession(UUID uniqueId, DialogSession session) {
        return sessions.get(uniqueId) == session;
    }

    private boolean requiresCaptcha(Player player) {
        if (!isOnAuthServer(player) || captcha.verified(player.getUniqueId())
                || auth.isAuthenticated(player.getUniqueId())) {
            return false;
        }
        AuthStatus status = auth.status(player.getUniqueId());
        return status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED;
    }

    private static final class DialogSession {
    }

    private record PendingSubmission(DialogSession session) {
    }

    private record PendingDialog(String actionId, String command, DialogSession session) {
    }
}
