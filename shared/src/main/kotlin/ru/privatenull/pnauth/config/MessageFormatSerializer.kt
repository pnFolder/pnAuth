package ru.privatenull.pnauth.config

import net.elytrium.serializer.custom.ClassSerializer
import ru.privatenull.pnauth.message.MessageFormat

/** Converts the human-readable YAML value into the strongly typed runtime format. */
class MessageFormatSerializer : ClassSerializer<MessageFormat, String>(
    MessageFormat::class.java,
    String::class.java
) {
    override fun serialize(value: MessageFormat): String = value.name

    override fun deserialize(value: String): MessageFormat = try {
        MessageFormat.parse(value)
    } catch (_: IllegalArgumentException) {
        System.err.println(
            "[pnAuth] Некорректное значение messages.format: '$value'. " +
                "Используется безопасное значение LEGACY. Допустимо: LEGACY, MINI_MESSAGE, JSON, PLAIN."
        )
        MessageFormat.LEGACY
    }
}
