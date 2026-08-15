package ru.privatenull.pnauth.platform

import net.kyori.adventure.text.Component
import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.display.PlayerDisplay
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID

/** Stable player facade which never exposes a Bungee, Velocity, or Bukkit type. */
interface PnPlayer {
    fun uniqueId(): UUID
    fun username(): String
    fun remoteAddress(): InetSocketAddress
    fun currentServer(): Optional<String>
    fun connected(): Boolean
    fun hasPermission(permission: String): Boolean
    fun sendMessage(message: String)
    fun sendMessage(message: Component)
    fun sendMessages(messages: Iterable<Component>)
    fun disconnect(reason: String)
    fun display(): PlayerDisplay
    fun dialogs(): PlayerDialogs
    fun scheduler(): PlatformScheduler

    fun ipAddress(): String {
        val address = remoteAddress().address
        return address?.hostAddress ?: remoteAddress().hostString
    }
}
