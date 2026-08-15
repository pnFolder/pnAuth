package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.title.Title;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.config.FeatureSettings;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.velocity.dialog.VelocityAuthFormCoordinator;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class VelocityAuthTasks implements AutoCloseable {
    private final Object plugin;
    private final ProxyServer proxy;
    private final AuthApi auth;
    private final AuthMessages messages;
    private final FeatureSettings settings;
    private final ProxySettings proxySettings;
    private final MessageFormat messageFormat;
    private final VelocityAuthFormCoordinator dialogs;
    private final Map<UUID, TaskPair> tasks = new ConcurrentHashMap<>();

    VelocityAuthTasks(Object plugin, ProxyServer proxy, AuthApi auth, AuthMessages messages,
                      FeatureSettings settings, ProxySettings proxySettings, MessageFormat messageFormat,
                              VelocityAuthFormCoordinator dialogs) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.auth = auth;
        this.messages = messages;
        this.settings = settings;
        this.proxySettings = proxySettings;
        this.messageFormat = messageFormat;
        this.dialogs = dialogs;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (!event.getPlayer().getCurrentServer().map(server -> server.getServerInfo().getName())
                .orElse("").equalsIgnoreCase(proxySettings.authServer())) return;
        Player player = event.getPlayer();
        cancel(player.getUniqueId());
        ScheduledTask reminder = null;
        long reminderSeconds = settings.reminderInterval().toSeconds();
        if (reminderSeconds > 0) {
            reminder = proxy.getScheduler().buildTask(plugin, () -> {
                AuthStatus status = auth.status(player.getUniqueId());
                if (proxy.getPlayer(player.getUniqueId()).isEmpty() || status == AuthStatus.AUTHENTICATED) {
                    cancel(player.getUniqueId());
                    return;
                }
                if (shouldSuppressCommandReminder(player, status)) return;
                player.sendMessage(VelocityMessages.component(messages.text(
                        status == AuthStatus.UNREGISTERED ? "reminder.register" : "reminder.login"), messageFormat));
                if (settings.actionBarEnabled()) {
                    player.sendActionBar(VelocityMessages.component(messages.text("display.actionbar"), messageFormat));
                }
                if (settings.titleEnabled()) {
                    player.showTitle(Title.title(
                            VelocityMessages.component(messages.text("display.title"), messageFormat),
                            VelocityMessages.component(messages.text("display.subtitle"), messageFormat)));
                }
            }).delay(reminderSeconds, TimeUnit.SECONDS)
                    .repeat(reminderSeconds, TimeUnit.SECONDS)
                    .schedule();
        }
        ScheduledTask timeout = proxy.getScheduler().buildTask(plugin, () -> {
            if (proxy.getPlayer(player.getUniqueId()).isPresent() && !auth.isAuthenticated(player.getUniqueId())) {
                player.disconnect(VelocityMessages.component(messages.text("kick.timeout"), messageFormat));
            }
            cancel(player.getUniqueId());
        }).delay(settings.authTimeout().toSeconds(), TimeUnit.SECONDS).schedule();
        tasks.put(player.getUniqueId(), new TaskPair(reminder, timeout));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        tasks.keySet().forEach(this::cancel);
    }

    private void cancel(UUID uniqueId) {
        TaskPair pair = tasks.remove(uniqueId);
        if (pair != null) {
            if (pair.reminder != null) pair.reminder.cancel();
            pair.timeout.cancel();
        }
    }

    private boolean shouldSuppressCommandReminder(Player player, AuthStatus status) {
        if (status != AuthStatus.UNREGISTERED && status != AuthStatus.UNAUTHENTICATED) return false;
        int protocol = player.getProtocolVersion().getProtocol();
        boolean platformSupportsDialogs = dialogs != null && dialogs.available();
        if (auth.shouldUseDialog(player.getUniqueId(), protocol, platformSupportsDialogs)) return true;
        return !auth.shouldUseCommandFallback(player.getUniqueId(), protocol, platformSupportsDialogs);
    }

    private record TaskPair(ScheduledTask reminder, ScheduledTask timeout) {
    }
}
