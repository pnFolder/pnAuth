package ru.privatenull.pnauth.platform

import net.kyori.adventure.text.Component
import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.display.PlayerDisplay
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID

/** Clean platform-neutral Player abstraction interface. */
interface Player {
    fun uniqueId(): UUID
    fun username(): String
    fun remoteAddress(): InetSocketAddress
    fun connected(): Boolean
    fun currentServer(): Optional<String>
    fun hasPermission(permission: String): Boolean
    fun sendMessage(message: String)
    fun sendMessage(message: Component)
    fun sendMessages(messages: Iterable<Component>)
    fun disconnect(reason: String)
    fun display(): PlayerDisplay
    fun dialogs(): PlayerDialogs
    fun scheduler(): PlatformScheduler
}
