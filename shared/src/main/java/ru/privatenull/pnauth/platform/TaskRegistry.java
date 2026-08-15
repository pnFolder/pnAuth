package ru.privatenull.pnauth.platform;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Named task directory. Replacing a task atomically cancels its previous instance. */
public interface TaskRegistry {
    TaskHandle delayed(String owner, String taskId, Duration delay, Runnable task);
    TaskHandle delayed(String owner, String taskId, PnPlayer player, Duration delay, Runnable task);
    TaskHandle repeating(String owner, String taskId, Duration initialDelay, Duration interval, Runnable task);
    TaskHandle repeating(String owner, String taskId, PnPlayer player, Duration initialDelay,
                         Duration interval, Runnable task);
    Optional<TaskHandle> find(String owner, String taskId, UUID playerId);
    Collection<TaskHandle> ownedBy(String owner);
    boolean cancel(String owner, String taskId, UUID playerId);
    int cancelAll(String owner);
    int cancelAll(UUID playerId);
    int cancelAll();
}
