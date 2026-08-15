package ru.privatenull.pnauth.paper

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.command.CommandRoots
import ru.privatenull.pnauth.config.PaperSettings
import java.util.UUID

/** Prevents unauthenticated backend players from interacting with the world. */
internal class PaperAccessListener(
    private val auth: AuthApi,
    private val settings: PaperSettings
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (settings.blockMovement && !authenticated(event.player.uniqueId) && event.hasChangedPosition()) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (settings.blockInteraction && !authenticated(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (settings.blockBreaking && !authenticated(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (settings.blockPlacing && !authenticated(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventory(event: InventoryOpenEvent) {
        val player = event.player
        if (player is Player && settings.blockInventory && !authenticated(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (settings.blockChat && !authenticated(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!settings.blockCommands || authenticated(event.player.uniqueId)) return
        val allowed = ALLOWED_COMMANDS.any { root -> CommandRoots.isExactRoot(event.message, root) }
        if (!allowed) event.isCancelled = true
    }

    private fun authenticated(playerId: UUID): Boolean {
        return auth.status(playerId) == AuthStatus.AUTHENTICATED
    }

    companion object {
        private val ALLOWED_COMMANDS: Set<String> = setOf(
            "auth", "pnauth", "register", "reg", "login", "l", "totp", "2fa", "status"
        )
    }
}
