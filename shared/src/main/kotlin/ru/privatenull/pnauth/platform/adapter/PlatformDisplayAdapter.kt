package ru.privatenull.pnauth.platform.adapter

import ru.privatenull.pnauth.display.PlayerDisplay
import ru.privatenull.pnauth.display.TitleBuilder
import java.util.UUID

/** Standardized multi-platform adapter for player displays, titles, and action bars. */
interface PlatformDisplayAdapter : PlayerDisplay {
    fun showTitle(uniqueId: UUID, builder: TitleBuilder)
    fun clearTitle(uniqueId: UUID)
}
