package ru.privatenull.pnauth.velocity

import net.kyori.adventure.text.Component
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.message.MessageComponents

object VelocityMessages {
    @JvmStatic
    @JvmOverloads
    fun component(message: String?, format: MessageFormat? = MessageFormat.LEGACY): Component {
        return MessageComponents.deserialize(message, format)
    }
}
