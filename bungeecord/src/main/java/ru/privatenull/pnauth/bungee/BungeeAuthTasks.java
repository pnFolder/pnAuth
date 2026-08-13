package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.config.FeatureSettings;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.message.AuthMessages;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class BungeeAuthTasks implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final AuthApi auth;
    private final AuthMessages messages;
    private final FeatureSettings settings;
    private final ProxySettings proxySettings;
    private final Map<UUID, TaskPair> tasks = new ConcurrentHashMap<>();

    BungeeAuthTasks(Plugin plugin, AuthApi auth, AuthMessages messages, FeatureSettings settings, ProxySettings proxySettings) {
        this.plugin = plugin;
        this.auth = auth;
        this.messages = messages;
        this.settings = settings;
        this.proxySettings = proxySettings;
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        if (!event.getServer().getInfo().getName().equalsIgnoreCase(proxySettings.authServer())) return;
        ProxiedPlayer player = event.getPlayer();
        cancel(player.getUniqueId());
        ScheduledTask reminder = null;
        long reminderSeconds = settings.reminderInterval().toSeconds();
        if (reminderSeconds > 0) {
            reminder = plugin.getProxy().getScheduler().schedule(plugin, () -> {
                AuthStatus status = auth.status(player.getUniqueId());
                if (!player.isConnected() || status == AuthStatus.AUTHENTICATED) {
                    cancel(player.getUniqueId());
                    return;
                }
                player.sendMessage(BungeeMessages.component(
                        messages.text(status == AuthStatus.UNREGISTERED ? "reminder.register" : "reminder.login"),
                        messages.format()));
                if (settings.titleEnabled()) {
                    Title title = plugin.getProxy().createTitle()
                            .title(BungeeMessages.component(messages.text("display.title"), messages.format()))
                            .subTitle(BungeeMessages.component(messages.text("display.subtitle"), messages.format()))
                            .fadeIn(0).stay(20).fadeOut(5);
                    player.sendTitle(title);
                }
            }, reminderSeconds, reminderSeconds, TimeUnit.SECONDS);
        }
        ScheduledTask timeout = plugin.getProxy().getScheduler().schedule(plugin, () -> {
            if (player.isConnected() && !auth.isAuthenticated(player.getUniqueId())) {
                player.disconnect(BungeeMessages.component(messages.text("kick.timeout"), messages.format()));
            }
            cancel(player.getUniqueId());
        }, settings.authTimeout().toSeconds(), TimeUnit.SECONDS);
        tasks.put(player.getUniqueId(), new TaskPair(reminder, timeout));
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
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

    private record TaskPair(ScheduledTask reminder, ScheduledTask timeout) {
    }
}
