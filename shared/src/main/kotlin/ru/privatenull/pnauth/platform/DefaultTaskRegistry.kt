package ru.privatenull.pnauth.platform

import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.function.Predicate

/** Thread-safe default implementation shared by every platform adapter. */
class DefaultTaskRegistry(
    private val scheduler: PlatformScheduler
) : TaskRegistry {

    private val tasks: ConcurrentMap<Key, TaskHandle> = ConcurrentHashMap()

    override fun delayed(owner: String, id: String, delay: Duration, task: Runnable): TaskHandle {
        return replace(Key(owner, id, null), scheduler.delayed(delay, task))
    }

    override fun delayed(owner: String, id: String, player: Player, delay: Duration, task: Runnable): TaskHandle {
        return replace(Key(owner, id, player.uniqueId()), scheduler.delayed(player, delay, task))
    }

    override fun repeating(owner: String, id: String, delay: Duration, interval: Duration, task: Runnable): TaskHandle {
        return replace(Key(owner, id, null), scheduler.repeating(delay, interval, task))
    }

    override fun repeating(
        owner: String,
        id: String,
        player: Player,
        delay: Duration,
        interval: Duration,
        task: Runnable
    ): TaskHandle {
        return replace(Key(owner, id, player.uniqueId()), scheduler.repeating(player, delay, interval, task))
    }

    override fun find(owner: String, id: String, playerId: UUID?): Optional<TaskHandle> {
        return Optional.ofNullable(tasks[Key(owner, id, playerId)])
    }

    override fun ownedBy(owner: String): Collection<TaskHandle> {
        return tasks.entries
            .filter { entry -> entry.key.owner == owner }
            .map { entry -> entry.value }
    }

    override fun cancel(owner: String, id: String, playerId: UUID?): Boolean {
        val handle = tasks.remove(Key(owner, id, playerId)) ?: return false
        handle.cancel()
        return true
    }

    override fun cancelAll(owner: String): Int = cancelMatching { key -> key.owner == owner }

    override fun cancelAll(playerId: UUID): Int = cancelMatching { key -> playerId == key.playerId }

    override fun cancelAll(): Int = cancelMatching { true }

    private fun replace(key: Key, next: TaskHandle): TaskHandle {
        val previous = tasks.put(key, next)
        previous?.cancel()
        return next
    }

    private fun cancelMatching(predicate: Predicate<Key>): Int {
        var count = 0
        for (entry in tasks.entries) {
            if (predicate.test(entry.key) && tasks.remove(entry.key, entry.value)) {
                entry.value.cancel()
                count++
            }
        }
        return count
    }

    private data class Key(
        val owner: String,
        val taskId: String,
        val playerId: UUID?
    ) {
        init {
            require(owner.isNotBlank()) { "owner" }
            require(taskId.isNotBlank()) { "taskId" }
        }
    }
}
