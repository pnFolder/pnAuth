package ru.privatenull.pnauth.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class DefaultTaskRegistryTest {
    @Test
    fun replacingNamedTaskCancelsPreviousHandle() {
        val scheduler = FakeScheduler()
        val registry = DefaultTaskRegistry(scheduler)

        val first = registry.repeating(
            "extension", "reminder", Duration.ZERO,
            Duration.ofSeconds(1)
        ) {}
        val second = registry.repeating(
            "extension", "reminder", Duration.ZERO,
            Duration.ofSeconds(1)
        ) {}

        assertTrue(first.cancelled())
        assertFalse(second.cancelled())
        assertSame(second, registry.find("extension", "reminder", null).orElseThrow())
    }

    @Test
    fun ownerCleanupCancelsEveryOwnedTask() {
        val scheduler = FakeScheduler()
        val registry = DefaultTaskRegistry(scheduler)
        registry.delayed("extension", "one", Duration.ZERO) {}
        registry.delayed("extension", "two", Duration.ZERO) {}

        assertEquals(2, registry.cancelAll("extension"))
        assertTrue(registry.ownedBy("extension").isEmpty())
    }

    private class FakeScheduler : PlatformScheduler {
        override fun execute(task: Runnable): TaskHandle = handle()
        override fun execute(player: PnPlayer, task: Runnable): TaskHandle = handle()
        override fun delayed(delay: Duration, task: Runnable): TaskHandle = handle()
        override fun delayed(player: PnPlayer, delay: Duration, task: Runnable): TaskHandle = handle()
        override fun repeating(delay: Duration, interval: Duration, task: Runnable): TaskHandle = handle()
        override fun repeating(player: PnPlayer, delay: Duration, interval: Duration, task: Runnable): TaskHandle = handle()

        private fun handle(): TaskHandle {
            return object : TaskHandle {
                private val isCancelled = AtomicBoolean()
                override fun cancelled(): Boolean = isCancelled.get()
                override fun cancel() { isCancelled.set(true) }
            }
        }
    }
}
