package ru.privatenull.pnauth.platform;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe default implementation shared by every platform adapter. */
public final class DefaultTaskRegistry implements TaskRegistry {
    private final PlatformScheduler scheduler;
    private final ConcurrentMap<Key, TaskHandle> tasks = new ConcurrentHashMap<>();

    public DefaultTaskRegistry(PlatformScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override public TaskHandle delayed(String owner, String id, Duration delay, Runnable task) {
        return replace(new Key(owner, id, null), scheduler.delayed(delay, task));
    }

    @Override public TaskHandle delayed(String owner, String id, PnPlayer player, Duration delay, Runnable task) {
        return replace(new Key(owner, id, player.uniqueId()), scheduler.delayed(player, delay, task));
    }

    @Override public TaskHandle repeating(String owner, String id, Duration delay, Duration interval, Runnable task) {
        return replace(new Key(owner, id, null), scheduler.repeating(delay, interval, task));
    }

    @Override
    public TaskHandle repeating(String owner, String id, PnPlayer player, Duration delay,
                                Duration interval, Runnable task) {
        return replace(new Key(owner, id, player.uniqueId()), scheduler.repeating(player, delay, interval, task));
    }

    @Override public Optional<TaskHandle> find(String owner, String id, UUID playerId) {
        return Optional.ofNullable(tasks.get(new Key(owner, id, playerId)));
    }

    @Override public Collection<TaskHandle> ownedBy(String owner) {
        return tasks.entrySet().stream().filter(entry -> entry.getKey().owner.equals(owner))
                .map(java.util.Map.Entry::getValue).toList();
    }

    @Override public boolean cancel(String owner, String id, UUID playerId) {
        TaskHandle handle = tasks.remove(new Key(owner, id, playerId));
        if (handle == null) return false;
        handle.cancel();
        return true;
    }

    @Override public int cancelAll(String owner) { return cancelMatching(key -> key.owner.equals(owner)); }
    @Override public int cancelAll(UUID playerId) { return cancelMatching(key -> playerId.equals(key.playerId)); }
    @Override public int cancelAll() { return cancelMatching(key -> true); }

    private TaskHandle replace(Key key, TaskHandle next) {
        TaskHandle previous = tasks.put(key, next);
        if (previous != null) previous.cancel();
        return next;
    }

    private int cancelMatching(java.util.function.Predicate<Key> predicate) {
        int count = 0;
        for (var entry : tasks.entrySet()) {
            if (predicate.test(entry.getKey()) && tasks.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().cancel();
                count++;
            }
        }
        return count;
    }

    private record Key(String owner, String taskId, UUID playerId) {
        private Key {
            if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner");
            if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId");
        }
    }
}
