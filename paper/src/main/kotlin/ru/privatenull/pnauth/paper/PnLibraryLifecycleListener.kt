package ru.privatenull.pnauth.paper

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent

/** Releases pnAuth's pnLibrary registration before the shared runtime shuts down or is unloaded by hand. */
class PnLibraryLifecycleListener(private val plugin: PnAuthPaperPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) {
        if (!event.plugin.name.equals("pnLibrary", ignoreCase = true)) return
        plugin.closePnLibrary()
    }
}
