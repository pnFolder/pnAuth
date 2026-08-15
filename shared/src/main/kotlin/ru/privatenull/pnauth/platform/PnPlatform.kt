package ru.privatenull.pnauth.platform

import ru.privatenull.pnauth.dialog.PlayerDialogs
import java.util.Optional
import java.util.UUID

/** Entry point for player and platform services exposed to extensions. */
interface PnPlatform {
    fun type(): PlatformType
    fun player(uniqueId: UUID): Optional<PnPlayer>
    fun player(username: String): Optional<PnPlayer>
    fun players(): Collection<PnPlayer>
    fun scheduler(): PlatformScheduler
    fun tasks(): TaskRegistry
    fun dialogs(): PlayerDialogs
}
