package ru.privatenull.pnauth.message

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/**
 * Единая точка преобразования пользовательских строк в Adventure-компоненты.
 * Платформенные модули получают уже готовый компонент и только переводят его
 * в собственный тип API. Формат никогда не определяется по содержимому строки.
 */
object MessageComponents {
    private val miniMessage = MiniMessage.miniMessage()
    private val gson = GsonComponentSerializer.gson()
    private val legacy = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()

    @JvmStatic
    fun deserialize(value: String?, format: MessageFormat?): Component {
        val source = value.orEmpty()
        return try {
            when (format ?: MessageFormat.LEGACY) {
                MessageFormat.LEGACY -> legacy.deserialize(source)
                MessageFormat.MINI_MESSAGE -> miniMessage.deserialize(source)
                MessageFormat.JSON -> gson.deserialize(source)
                MessageFormat.PLAIN -> Component.text(source)
            }
        } catch (_: RuntimeException) {
            // Ошибка в пользовательском шаблоне не должна ломать обработчик события прокси.
            Component.text(MessageRenderers.toLegacy(source, format))
        }
    }

    @JvmStatic
    fun serializeJson(component: Component?): String = gson.serialize(component ?: Component.empty())
}
