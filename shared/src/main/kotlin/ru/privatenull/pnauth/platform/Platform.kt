package ru.privatenull.pnauth.platform

import ru.privatenull.pnauth.dialog.PlayerDialogs
import java.util.Optional
import java.util.UUID

/** Entry point for player and platform services exposed to extensions. */
interface Platform {
    fun type(): PlatformType
    fun player(uniqueId: UUID): Optional<Player>
    fun player(username: String): Optional<Player>
    fun players(): Collection<Player>
    fun scheduler(): PlatformScheduler
    fun tasks(): TaskRegistry
    fun dialogs(): PlayerDialogs
}
