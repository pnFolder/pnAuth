@file:Suppress("DEPRECATION")
package ru.privatenull.pnauth.bungee

import net.kyori.adventure.text.Component
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.chat.ComponentSerializer
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.message.MessageRenderers

internal object BungeeMessages {

    @JvmStatic
    fun component(message: String?, format: MessageFormat?): BaseComponent {
        return TextComponent.fromArray(*components(message, format))
    }

    @JvmStatic
    fun components(message: String?, format: MessageFormat?): Array<BaseComponent> {
        val value = message ?: ""
        val selected = format ?: MessageFormat.LEGACY

        if (selected == MessageFormat.JSON) {
            try {
                return ComponentSerializer.parse(value)
            } catch (ignored: Throwable) {
                // Fall through to legacy text parsing
            }
        }

        if (selected == MessageFormat.MINI_MESSAGE || (value.contains("<") && value.contains(">"))) {
            return BungeeComponentAdapter.parse(value)
        }

        val legacy = MessageRenderers.toLegacy(value, selected)
        return TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', legacy))
    }

    @JvmStatic
    fun adventureComponent(message: String?, format: MessageFormat?): Component {
        val value = message ?: ""
        val selected = format ?: MessageFormat.LEGACY
        val legacy = MessageRenderers.toLegacy(value, selected)
        val plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', legacy))
        return Component.text(plain ?: "")
    }
}
