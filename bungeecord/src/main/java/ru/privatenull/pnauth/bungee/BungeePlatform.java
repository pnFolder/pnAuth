package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import ru.privatenull.pnauth.dialog.PlayerDialogs;
import ru.privatenull.pnauth.display.PlayerDisplay;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.platform.PlatformScheduler;
import ru.privatenull.pnauth.platform.PlatformType;
import ru.privatenull.pnauth.platform.PnPlatform;
import ru.privatenull.pnauth.platform.PnPlayer;
import ru.privatenull.pnauth.platform.TaskHandle;
import ru.privatenull.pnauth.platform.TaskRegistry;
import ru.privatenull.pnauth.platform.DefaultTaskRegistry;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** BungeeCord adapter for the public pnAuth platform API. */
public final class BungeePlatform implements PnPlatform {
    private final ProxyServer proxy;
    private final PlayerDisplay display;
    private final MessageFormat messageFormat;
    private final PlayerDialogs dialogs;
    private final PlatformScheduler scheduler;
    private final TaskRegistry tasks;

    public BungeePlatform(Plugin plugin, PlayerDisplay display, MessageFormat messageFormat,
                          PlayerDialogs dialogs) {
        this.proxy = plugin.getProxy();
        this.display = display;
        this.messageFormat = messageFormat;
        this.dialogs = dialogs;
        this.scheduler = new BungeeScheduler(plugin);
        this.tasks = new DefaultTaskRegistry(scheduler);
    }

    @Override public PlatformType type() { return PlatformType.BUNGEECORD; }
    @Override public Optional<PnPlayer> player(UUID uniqueId) { return wrap(proxy.getPlayer(uniqueId)); }
    @Override public Optional<PnPlayer> player(String username) { return wrap(proxy.getPlayer(username)); }
    @Override public PlatformScheduler scheduler() { return scheduler; }
    @Override public TaskRegistry tasks() { return tasks; }
    @Override public PlayerDialogs dialogs() { return dialogs; }

    @Override
    public Collection<PnPlayer> players() {
        return proxy.getPlayers().stream().map(Player::new).map(PnPlayer.class::cast).toList();
    }

    private Optional<PnPlayer> wrap(ProxiedPlayer player) {
        return Optional.ofNullable(player).map(Player::new);
    }

    private final class Player implements PnPlayer {
        private final ProxiedPlayer delegate;

        private Player(ProxiedPlayer delegate) { this.delegate = delegate; }
        @Override public UUID uniqueId() { return delegate.getUniqueId(); }
        @Override public String username() { return delegate.getName(); }
        @Override public InetSocketAddress remoteAddress() { return delegate.getAddress(); }
        @Override public boolean connected() { return delegate.isConnected(); }
        @Override public boolean hasPermission(String permission) { return delegate.hasPermission(permission); }
        @Override public PlayerDisplay display() { return display; }
        @Override public PlayerDialogs dialogs() { return dialogs; }
        @Override public PlatformScheduler scheduler() { return scheduler; }

        @Override
        public Optional<String> currentServer() {
            return Optional.ofNullable(delegate.getServer())
                    .map(connection -> connection.getInfo().getName());
        }

        @Override
        public void sendMessage(String message) {
            delegate.sendMessage(BungeeMessages.component(message, messageFormat));
        }

        @Override public void sendMessage(net.kyori.adventure.text.Component message) {
            delegate.sendMessage(net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer.get()
                    .serialize(message));
        }

        @Override public void sendMessages(Iterable<? extends net.kyori.adventure.text.Component> messages) {
            messages.forEach(this::sendMessage);
        }

        @Override
        public void disconnect(String reason) {
            delegate.disconnect(BungeeMessages.component(reason, messageFormat));
        }
    }

    private static final class BungeeScheduler implements PlatformScheduler {
        private final Plugin plugin;

        private BungeeScheduler(Plugin plugin) { this.plugin = plugin; }
        @Override public TaskHandle execute(Runnable task) { return delayed(Duration.ZERO, task); }
        @Override public TaskHandle execute(PnPlayer player, Runnable task) { return execute(task); }
        @Override public TaskHandle delayed(PnPlayer player, Duration delay, Runnable task) { return delayed(delay, task); }
        @Override public TaskHandle repeating(PnPlayer player, Duration initialDelay, Duration interval, Runnable task) {
            return repeating(initialDelay, interval, task);
        }

        @Override
        public TaskHandle delayed(Duration delay, Runnable task) {
            ScheduledTask scheduled = plugin.getProxy().getScheduler().schedule(
                    plugin, task, milliseconds(delay), TimeUnit.MILLISECONDS);
            return new ScheduledHandle(scheduled);
        }

        @Override
        public TaskHandle repeating(Duration initialDelay, Duration interval, Runnable task) {
            ScheduledTask scheduled = plugin.getProxy().getScheduler().schedule(
                    plugin, task, milliseconds(initialDelay), Math.max(1L, milliseconds(interval)),
                    TimeUnit.MILLISECONDS);
            return new ScheduledHandle(scheduled);
        }

        private static long milliseconds(Duration duration) {
            return duration == null ? 0L : Math.max(0L, duration.toMillis());
        }
    }

    private static final class ScheduledHandle implements TaskHandle {
        private final ScheduledTask task;
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

        private ScheduledHandle(ScheduledTask task) { this.task = task; }
        @Override public boolean cancelled() { return cancelled.get(); }
        @Override public void cancel() {
            if (cancelled.compareAndSet(false, true)) task.cancel();
        }
    }
}
