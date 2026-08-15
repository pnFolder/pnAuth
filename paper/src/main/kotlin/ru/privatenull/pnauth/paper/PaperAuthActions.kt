package ru.privatenull.pnauth.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ru.privatenull.pnauth.command.AuthPlatformBridge
import ru.privatenull.pnauth.message.AuthMessages
import java.util.UUID

/** Paper/Folia side effects requested by shared authentication events. */
internal class PaperAuthActions(
    private val plugin: Plugin,
    private val messages: AuthMessages
) : AuthPlatformBridge {

    override fun authenticated(uniqueId: UUID) {}
    override fun authenticated(username: String) {}

    override fun loggedOut(uniqueId: UUID) {
        disconnect(uniqueId, messages.text("logout.disconnect"))
    }

    override fun accountDeleted(uniqueId: UUID) {
        disconnect(uniqueId, messages.text("unregister.disconnect"))
    }

    override fun accountDeleted(username: String) {
        val player = Bukkit.getPlayerExact(username)
        player?.scheduler?.run(
            plugin,
            { player.kick(Component.text(messages.text("unregister.disconnect"))) },
            null
        )
    }

    override fun broadcast(message: String) {
        val rendered = messages.text("broadcast.message", mapOf("message" to message))
        Bukkit.getOnlinePlayers().forEach { player ->
            player.scheduler.run(plugin, { player.sendMessage(rendered) }, null)
        }
    }

    private fun disconnect(uniqueId: UUID, reason: String) {
        val player = Bukkit.getPlayer(uniqueId)
        player?.scheduler?.run(
            plugin,
            { player.kick(Component.text(reason)) },
            null
        )
    }
}
