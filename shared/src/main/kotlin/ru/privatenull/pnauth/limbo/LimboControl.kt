package ru.privatenull.pnauth.limbo

import ru.privatenull.pnauth.display.BossBarColor
import ru.privatenull.pnauth.display.BossBarOverlay
import java.util.Optional
import java.util.UUID

interface LimboControl {
    fun apiVersion(): Int

    fun isPlayerConnected(playerId: UUID): Boolean

    fun addBossBar(
        playerId: UUID, barId: UUID, title: String, progress: Float,
        color: BossBarColor, overlay: BossBarOverlay
    ): LimboControlResult

    fun updateBossBarProgress(playerId: UUID, barId: UUID, progress: Float): LimboControlResult

    fun updateBossBarTitle(playerId: UUID, barId: UUID, title: String): LimboControlResult

    fun removeBossBar(playerId: UUID, barId: UUID): LimboControlResult

    fun showDialog(playerId: UUID, dialogJson: String): LimboControlResult

    fun clearDialog(playerId: UUID): LimboControlResult

    fun pollDialogEvent(): Optional<LimboDialogEvent>
}
