package ru.privatenull.pnauth.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.command.CommandRoots;
import ru.privatenull.pnauth.config.PaperSettings;

import java.util.Set;

/** Prevents unauthenticated backend players from interacting with the world. */
final class PaperAccessListener implements Listener {
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "auth", "pnauth", "register", "reg", "login", "l", "totp", "2fa", "status");
    private final AuthApi auth;
    private final PaperSettings settings;

    PaperAccessListener(AuthApi auth, PaperSettings settings) {
        this.auth = auth;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (settings.blockMovement() && !authenticated(event.getPlayer().getUniqueId())
                && event.hasChangedPosition()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) { if (settings.blockInteraction() && !authenticated(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { if (settings.blockBreaking() && !authenticated(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { if (settings.blockPlacing() && !authenticated(event.getPlayer().getUniqueId())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof org.bukkit.entity.Player player
                && settings.blockInventory() && !authenticated(player.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) { if (settings.blockChat() && !authenticated(event.getPlayer().getUniqueId())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!settings.blockCommands() || authenticated(event.getPlayer().getUniqueId())) return;
        boolean allowed = ALLOWED_COMMANDS.stream().anyMatch(root -> CommandRoots.isExactRoot(event.getMessage(), root));
        if (!allowed) event.setCancelled(true);
    }

    private boolean authenticated(java.util.UUID playerId) {
        return auth.status(playerId) == AuthStatus.AUTHENTICATED;
    }
}
