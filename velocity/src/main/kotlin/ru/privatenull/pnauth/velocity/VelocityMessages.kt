package ru.privatenull.pnauth.velocity

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.message.MessageRenderers

object VelocityMessages {
    @JvmStatic
    @JvmOverloads
    fun component(message: String?, format: MessageFormat? = MessageFormat.LEGACY): Component {
        val value = message ?: ""
        val selected = format ?: MessageFormat.LEGACY
        return try {
            if (selected == MessageFormat.MINI_MESSAGE || (value.contains("<") && value.contains(">"))) {
                return MiniMessage.miniMessage().deserialize(value)
            }
            when (selected) {
                MessageFormat.JSON -> GsonComponentSerializer.gson().deserialize(value)
                MessageFormat.PLAIN -> Component.text(value)
                else -> LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(MessageRenderers.toLegacy(value, selected))
            }
        } catch (ignored: RuntimeException) {
            // Keep a malformed user template harmless instead of failing a proxy event.
            Component.text(MessageRenderers.toLegacy(value, selected))
        }
    }
}
