package ru.privatenull.pnauth.platform;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class DefaultTaskRegistryTest {
    @Test
    void replacingNamedTaskCancelsPreviousHandle() {
        FakeScheduler scheduler = new FakeScheduler();
        DefaultTaskRegistry registry = new DefaultTaskRegistry(scheduler);

        TaskHandle first = registry.repeating("extension", "reminder", Duration.ZERO,
                Duration.ofSeconds(1), () -> { });
        TaskHandle second = registry.repeating("extension", "reminder", Duration.ZERO,
                Duration.ofSeconds(1), () -> { });

        assertTrue(first.cancelled());
        assertFalse(second.cancelled());
        assertSame(second, registry.find("extension", "reminder", null).orElseThrow());
    }

    @Test
    void ownerCleanupCancelsEveryOwnedTask() {
        FakeScheduler scheduler = new FakeScheduler();
        DefaultTaskRegistry registry = new DefaultTaskRegistry(scheduler);
        registry.delayed("extension", "one", Duration.ZERO, () -> { });
        registry.delayed("extension", "two", Duration.ZERO, () -> { });

        assertEquals(2, registry.cancelAll("extension"));
        assertTrue(registry.ownedBy("extension").isEmpty());
    }

    private static final class FakeScheduler implements PlatformScheduler {
        @Override public TaskHandle execute(Runnable task) { return handle(); }
        @Override public TaskHandle execute(PnPlayer player, Runnable task) { return handle(); }
        @Override public TaskHandle delayed(Duration delay, Runnable task) { return handle(); }
        @Override public TaskHandle delayed(PnPlayer player, Duration delay, Runnable task) { return handle(); }
        @Override public TaskHandle repeating(Duration delay, Duration interval, Runnable task) { return handle(); }
        @Override public TaskHandle repeating(PnPlayer player, Duration delay, Duration interval, Runnable task) { return handle(); }
        private TaskHandle handle() {
            return new TaskHandle() {
                private final AtomicBoolean cancelled = new AtomicBoolean();
                @Override public boolean cancelled() { return cancelled.get(); }
                @Override public void cancel() { cancelled.set(true); }
            };
        }
    }
}
