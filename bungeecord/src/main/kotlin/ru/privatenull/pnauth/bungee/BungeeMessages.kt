@file:Suppress("DEPRECATION")
package ru.privatenull.pnauth.bungee

import net.kyori.adventure.text.Component
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.chat.ComponentSerializer
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.message.MessageComponents

internal object BungeeMessages {

    @JvmStatic
    fun component(message: String?, format: MessageFormat?): BaseComponent {
        return TextComponent.fromArray(*components(message, format))
    }

    @JvmStatic
    fun components(message: String?, format: MessageFormat?): Array<BaseComponent> {
        val component = MessageComponents.deserialize(message, format)
        return ComponentSerializer.parse(MessageComponents.serializeJson(component))
    }

    @JvmStatic
    fun adventureComponent(message: String?, format: MessageFormat?): Component {
        return MessageComponents.deserialize(message, format)
    }
}
