package ru.privatenull.pnauth.platform;

import ru.privatenull.pnauth.dialog.PlayerDialogs;
import ru.privatenull.pnauth.dialog.UnsupportedPlayerDialogs;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Empty platform used until the hosting adapter has completed initialization. */
public final class UnavailablePlatform implements PnPlatform {
    private final PlayerDialogs dialogs = new UnsupportedPlayerDialogs();
    private final PlatformScheduler scheduler = new ImmediateScheduler();
    private final TaskRegistry tasks = new DefaultTaskRegistry(scheduler);

    @Override public PlatformType type() { return PlatformType.PAPER; }
    @Override public Optional<PnPlayer> player(UUID uniqueId) { return Optional.empty(); }
    @Override public Optional<PnPlayer> player(String username) { return Optional.empty(); }
    @Override public Collection<PnPlayer> players() { return List.of(); }
    @Override public PlatformScheduler scheduler() { return scheduler; }
    @Override public TaskRegistry tasks() { return tasks; }
    @Override public PlayerDialogs dialogs() { return dialogs; }

    private static final class ImmediateScheduler implements PlatformScheduler {
        @Override public TaskHandle execute(Runnable task) { task.run(); return DoneTask.INSTANCE; }
        @Override public TaskHandle execute(PnPlayer player, Runnable task) { return execute(task); }
        @Override public TaskHandle delayed(Duration delay, Runnable task) { return DoneTask.INSTANCE; }
        @Override public TaskHandle delayed(PnPlayer player, Duration delay, Runnable task) { return DoneTask.INSTANCE; }
        @Override public TaskHandle repeating(Duration initialDelay, Duration interval, Runnable task) { return DoneTask.INSTANCE; }
        @Override public TaskHandle repeating(PnPlayer player, Duration initialDelay, Duration interval, Runnable task) { return DoneTask.INSTANCE; }
    }

    private enum DoneTask implements TaskHandle {
        INSTANCE;
        @Override public boolean cancelled() { return true; }
        @Override public void cancel() { }
    }
}
