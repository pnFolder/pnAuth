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
        if (format == MessageFormat.JSON) {
            try {
                return ComponentSerializer.parse(message ?: "")
            } catch (ignored: RuntimeException) {
                // Fall through to inert legacy text for a malformed administrator template.
            }
        }
        val legacy = MessageRenderers.toLegacy(message ?: "", format ?: MessageFormat.LEGACY)
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
