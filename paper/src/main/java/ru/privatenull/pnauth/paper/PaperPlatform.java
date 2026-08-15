package ru.privatenull.pnauth.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.privatenull.pnauth.dialog.PlayerDialogs;
import ru.privatenull.pnauth.display.PlayerDisplay;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** Paper and Folia adapter for the public pnAuth platform API. */
public final class PaperPlatform implements PnPlatform {
    private final Plugin plugin;
    private final PlayerDisplay display;
    private final PlayerDialogs dialogs;
    private final PlatformScheduler scheduler = new Scheduler();
    private final TaskRegistry tasks = new DefaultTaskRegistry(scheduler);
    private final PlatformType type;

    public PaperPlatform(Plugin plugin, PlayerDisplay display, PlayerDialogs dialogs) {
        this.plugin = plugin;
        this.display = display;
        this.dialogs = dialogs;
        this.type = Bukkit.getName().toLowerCase().contains("folia")
                ? PlatformType.FOLIA
                : PlatformType.PAPER;
    }

    @Override public PlatformType type() { return type; }
    @Override public Optional<PnPlayer> player(UUID uniqueId) { return wrap(Bukkit.getPlayer(uniqueId)); }
    @Override public Optional<PnPlayer> player(String username) { return wrap(Bukkit.getPlayerExact(username)); }
    @Override public PlatformScheduler scheduler() { return scheduler; }
    @Override public TaskRegistry tasks() { return tasks; }
    @Override public PlayerDialogs dialogs() { return dialogs; }

    @Override
    public Collection<PnPlayer> players() {
        return Bukkit.getOnlinePlayers().stream().map(Wrapper::new).map(PnPlayer.class::cast).toList();
    }

    private Optional<PnPlayer> wrap(Player player) {
        return Optional.ofNullable(player).map(Wrapper::new);
    }

    private final class Wrapper implements PnPlayer {
        private final Player delegate;

        private Wrapper(Player delegate) { this.delegate = delegate; }
        @Override public UUID uniqueId() { return delegate.getUniqueId(); }
        @Override public String username() { return delegate.getName(); }
        @Override public InetSocketAddress remoteAddress() { return delegate.getAddress(); }
        @Override public Optional<String> currentServer() { return Optional.of(Bukkit.getServer().getName()); }
        @Override public boolean connected() { return delegate.isConnected(); }
        @Override public boolean hasPermission(String permission) { return delegate.hasPermission(permission); }
        @Override public PlayerDisplay display() { return display; }
        @Override public PlayerDialogs dialogs() { return dialogs; }
        @Override public PlatformScheduler scheduler() { return scheduler; }
        @Override public void sendMessage(String message) { delegate.sendMessage(message); }
        @Override public void sendMessage(net.kyori.adventure.text.Component message) { delegate.sendMessage(message); }
        @Override public void sendMessages(Iterable<? extends net.kyori.adventure.text.Component> messages) {
            messages.forEach(delegate::sendMessage);
        }
        @Override public void disconnect(String reason) { delegate.kick(net.kyori.adventure.text.Component.text(reason)); }
    }

    private final class Scheduler implements PlatformScheduler {
        @Override public TaskHandle execute(Runnable task) { return delayed(Duration.ZERO, task); }
        @Override public TaskHandle execute(PnPlayer player, Runnable task) { return delayed(player, Duration.ZERO, task); }

        @Override
        public TaskHandle delayed(Duration delay, Runnable task) {
            var scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(
                    plugin, ignored -> task.run(), ticks(delay));
            return handle(scheduled::cancel);
        }

        @Override
        public TaskHandle delayed(PnPlayer player, Duration delay, Runnable task) {
            Player delegate = Bukkit.getPlayer(player.uniqueId());
            if (delegate == null) return handle(() -> { });
            var scheduled = delegate.getScheduler().runDelayed(
                    plugin, ignored -> task.run(), null, ticks(delay));
            return handle(scheduled::cancel);
        }

        @Override
        public TaskHandle repeating(Duration initialDelay, Duration interval, Runnable task) {
            var scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin, ignored -> task.run(), ticks(initialDelay), ticks(interval));
            return handle(scheduled::cancel);
        }

        @Override
        public TaskHandle repeating(PnPlayer player, Duration initialDelay, Duration interval, Runnable task) {
            Player delegate = Bukkit.getPlayer(player.uniqueId());
            if (delegate == null) return handle(() -> { });
            var scheduled = delegate.getScheduler().runAtFixedRate(
                    plugin, ignored -> task.run(), null, ticks(initialDelay), ticks(interval));
            return handle(scheduled::cancel);
        }

        private long ticks(Duration duration) {
            long millis = duration == null ? 0L : Math.max(0L, duration.toMillis());
            return Math.max(1L, (millis + 49L) / 50L);
        }

        private TaskHandle handle(Runnable cancellation) {
            return new TaskHandle() {
                private final AtomicBoolean cancelled = new AtomicBoolean();
                @Override public boolean cancelled() { return cancelled.get(); }
                @Override public void cancel() {
                    if (cancelled.compareAndSet(false, true)) cancellation.run();
                }
            };
        }
    }
}
