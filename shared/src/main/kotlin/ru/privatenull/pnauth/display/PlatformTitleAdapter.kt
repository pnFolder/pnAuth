package ru.privatenull.pnauth.display

import java.util.UUID

/**
 * Unified interface for sending constructed TitleBuilder titles across proxy platforms (BungeeCord, Velocity, Paper).
 */
interface PlatformTitleAdapter {

    /**
     * Renders a TitleBuilder instance to a player by unique ID.
     */
    fun showTitle(uniqueId: UUID, builder: TitleBuilder)

    /**
     * Clears any active title on the player's screen.
     */
    fun clearTitle(uniqueId: UUID)
}
