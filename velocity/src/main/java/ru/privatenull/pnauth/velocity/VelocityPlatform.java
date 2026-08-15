package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
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

/** Velocity adapter for the public pnAuth platform API. */
public final class VelocityPlatform implements PnPlatform {
    private final Object plugin;
    private final ProxyServer proxy;
    private final PlayerDisplay display;
    private final MessageFormat messageFormat;
    private final PlayerDialogs dialogs;
    private final PlatformScheduler scheduler;
    private final TaskRegistry tasks;

    public VelocityPlatform(Object plugin, ProxyServer proxy, PlayerDisplay display, MessageFormat messageFormat,
                            PlayerDialogs dialogs) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.display = display;
        this.messageFormat = messageFormat;
        this.dialogs = dialogs;
        this.scheduler = new VelocityScheduler();
        this.tasks = new DefaultTaskRegistry(scheduler);
    }

    @Override public PlatformType type() { return PlatformType.VELOCITY; }
    @Override public Optional<PnPlayer> player(UUID uniqueId) { return proxy.getPlayer(uniqueId).map(Wrapper::new); }
    @Override public Optional<PnPlayer> player(String username) { return proxy.getPlayer(username).map(Wrapper::new); }
    @Override public PlatformScheduler scheduler() { return scheduler; }
    @Override public TaskRegistry tasks() { return tasks; }
    @Override public PlayerDialogs dialogs() { return dialogs; }

    @Override
    public Collection<PnPlayer> players() {
        return proxy.getAllPlayers().stream().map(Wrapper::new).map(PnPlayer.class::cast).toList();
    }

    private final class Wrapper implements PnPlayer {
        private final Player delegate;

        private Wrapper(Player delegate) { this.delegate = delegate; }
        @Override public UUID uniqueId() { return delegate.getUniqueId(); }
        @Override public String username() { return delegate.getUsername(); }
        @Override public InetSocketAddress remoteAddress() { return delegate.getRemoteAddress(); }
        @Override public boolean connected() { return delegate.isActive(); }
        @Override public boolean hasPermission(String permission) { return delegate.hasPermission(permission); }
        @Override public PlayerDisplay display() { return display; }
        @Override public PlayerDialogs dialogs() { return dialogs; }
        @Override public PlatformScheduler scheduler() { return scheduler; }

        @Override
        public Optional<String> currentServer() {
            return delegate.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName());
        }

        @Override public void sendMessage(String message) {
            delegate.sendMessage(VelocityMessages.component(message, messageFormat));
        }
        @Override public void sendMessage(net.kyori.adventure.text.Component message) { delegate.sendMessage(message); }
        @Override public void sendMessages(Iterable<? extends net.kyori.adventure.text.Component> messages) {
            messages.forEach(delegate::sendMessage);
        }

        @Override public void disconnect(String reason) {
            delegate.disconnect(VelocityMessages.component(reason, messageFormat));
        }
    }

    private final class VelocityScheduler implements PlatformScheduler {
        @Override public TaskHandle execute(Runnable task) { return delayed(Duration.ZERO, task); }
        @Override public TaskHandle execute(PnPlayer player, Runnable task) { return execute(task); }
        @Override public TaskHandle delayed(PnPlayer player, Duration delay, Runnable task) { return delayed(delay, task); }
        @Override public TaskHandle repeating(PnPlayer player, Duration initialDelay, Duration interval, Runnable task) {
            return repeating(initialDelay, interval, task);
        }

        @Override
        public TaskHandle delayed(Duration delay, Runnable task) {
            ScheduledTask scheduled = proxy.getScheduler().buildTask(plugin, task)
                    .delay(milliseconds(delay), TimeUnit.MILLISECONDS)
                    .schedule();
            return new ScheduledHandle(scheduled);
        }

        @Override
        public TaskHandle repeating(Duration initialDelay, Duration interval, Runnable task) {
            ScheduledTask scheduled = proxy.getScheduler().buildTask(plugin, task)
                    .delay(milliseconds(initialDelay), TimeUnit.MILLISECONDS)
                    .repeat(Math.max(1L, milliseconds(interval)), TimeUnit.MILLISECONDS)
                    .schedule();
            return new ScheduledHandle(scheduled);
        }

        private long milliseconds(Duration duration) {
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
