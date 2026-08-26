package ru.privatenull.pnauth.platform

import java.time.Duration

/** Platform-independent scheduler. Player-bound tasks are safe to use on Folia. */
interface PlatformScheduler {
    fun execute(task: Runnable): TaskHandle
    fun execute(player: Player, task: Runnable): TaskHandle
    fun delayed(delay: Duration, task: Runnable): TaskHandle
    fun delayed(player: Player, delay: Duration, task: Runnable): TaskHandle
    fun repeating(initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle
    fun repeating(player: Player, initialDelay: Duration, interval: Duration, task: Runnable): TaskHandle
}
