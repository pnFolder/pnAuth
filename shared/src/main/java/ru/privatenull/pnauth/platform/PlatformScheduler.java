package ru.privatenull.pnauth.platform;

import java.time.Duration;

/** Platform-independent scheduler. Player-bound tasks are safe to use on Folia. */
public interface PlatformScheduler {
    TaskHandle execute(Runnable task);
    TaskHandle execute(PnPlayer player, Runnable task);
    TaskHandle delayed(Duration delay, Runnable task);
    TaskHandle delayed(PnPlayer player, Duration delay, Runnable task);
    TaskHandle repeating(Duration initialDelay, Duration interval, Runnable task);
    TaskHandle repeating(PnPlayer player, Duration initialDelay, Duration interval, Runnable task);
}
