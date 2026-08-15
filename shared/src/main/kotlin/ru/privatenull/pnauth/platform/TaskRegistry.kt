package ru.privatenull.pnauth.platform

import java.time.Duration
import java.util.Optional
import java.util.UUID

/** Named task directory. Replacing a task atomically cancels its previous instance. */
interface TaskRegistry {
    fun delayed(owner: String, taskId: String, delay: Duration, task: Runnable): TaskHandle
    fun delayed(owner: String, taskId: String, player: PnPlayer, delay: Duration, task: Runnable): TaskHandle
    fun repeating(owner: String, taskId: String, initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle
    fun repeating(
        owner: String,
        taskId: String,
        player: PnPlayer,
        initialDelay: Duration,
        interval: Duration,
        task: Runnable
    ): TaskHandle

    fun find(owner: String, taskId: String, playerId: UUID?): Optional<TaskHandle>
    fun ownedBy(owner: String): Collection<TaskHandle>
    fun cancel(owner: String, taskId: String, playerId: UUID?): Boolean
    fun cancelAll(owner: String): Int
    fun cancelAll(playerId: UUID): Int
    fun cancelAll(): Int
}
