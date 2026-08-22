package ru.privatenull.pnauth.message

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * Единая точка преобразования пользовательских строк в Adventure-компоненты.
 * Платформенные модули получают уже готовый компонент и только переводят его
 * в собственный тип API. Формат никогда не определяется по содержимому строки.
 */
object MessageComponents {
    private const val AVAILABLE_AUTH_ACTIONS = "open_dialog"
    private val authActions = TagResolver.resolver("auth") { arguments, _ ->
        val action = arguments.popOr("Для тега <auth> требуется название действия").value()
        when (action.lowercase()) {
            "open_dialog" -> Tag.styling(ClickEvent.runCommand("/_pnauthui open"))
            else -> throw IllegalArgumentException(
                "Неизвестное действие pnAuth 'auth:$action'. Доступные действия: $AVAILABLE_AUTH_ACTIONS."
            )
        }
    }
    private val miniMessage = MiniMessage.builder().editTags { it.resolver(authActions) }.build()
    private val gson = GsonComponentSerializer.gson()
    private val legacy = LegacyComponentSerializer.builder()
        .character('&')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    private val legacySection = LegacyComponentSerializer.builder()
        .character('§')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    private val plain = PlainTextComponentSerializer.plainText()

    @JvmStatic
    fun deserialize(value: String?, format: MessageFormat?): Component {
        val source = value.orEmpty()
        return try {
            // pnAuth actions are an explicit MiniMessage extension and remain portable
            // even when ordinary chat messages use LEGACY, JSON or PLAIN.
            if (source.contains("<auth:", ignoreCase = true)) return miniMessage.deserialize(source)
            when (format ?: MessageFormat.LEGACY) {
                MessageFormat.LEGACY -> legacy.deserialize(source)
                MessageFormat.MINI_MESSAGE -> miniMessage.deserialize(source)
                MessageFormat.JSON -> gson.deserialize(source)
                MessageFormat.PLAIN -> Component.text(source)
            }
        } catch (exception: RuntimeException) {
            if (source.contains("<auth:", ignoreCase = true)) {
                System.err.println("[pnAuth] Не удалось разобрать действие в сообщении: ${exception.message}")
            }
            // Ошибка в пользовательском шаблоне не должна ломать обработчик события прокси.
            Component.text(MessageRenderers.toLegacy(source, format))
        }
    }

    @JvmStatic
    fun serializeJson(component: Component?): String = gson.serialize(component ?: Component.empty())

    /** Строка с нативным legacy-символом Minecraft §. Hover/click в строковом формате не представимы. */
    @JvmStatic
    fun serializeLegacySection(component: Component?): String = legacySection.serialize(component ?: Component.empty())

    /** Строка с удобным для YAML символом &, включая HEX-последовательности. */
    @JvmStatic
    fun serializeLegacyAmpersand(component: Component?): String = legacy.serialize(component ?: Component.empty())

    /** Видимый текст без цветов и событий — для логов и внешних мессенджеров. */
    @JvmStatic
    fun serializePlain(component: Component?): String = plain.serialize(component ?: Component.empty())
}
