package ru.privatenull.pnauth.message

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageComponentsTest {
    @Test
    fun `auth open dialog tag creates a portable click and hover component`() {
        val component = MessageComponents.deserialize(
            "<auth:open_dialog><hover:show_text:'<gray>Подсказка</gray>'>[Повторить]</hover></auth>",
            MessageFormat.MINI_MESSAGE
        )
        val json = MessageComponents.serializeJson(component)

        assertTrue(json.contains("pnauth:open_dialog"))
        assertTrue(json.contains("custom"))
        assertTrue(json.contains("show_text"))
        assertTrue(json.contains("Повторить"))
    }

    @Test
    fun `auth action remains available when ordinary messages use legacy`() {
        val component = MessageComponents.deserialize(
            "<auth:open_dialog>[Повторить]</auth>",
            MessageFormat.LEGACY
        )

        assertTrue(MessageComponents.serializeJson(component).contains("pnauth:open_dialog"))
    }

    @Test
    fun `auth action remains available with every configured message format`() {
        MessageFormat.entries.forEach { format ->
            val component = MessageComponents.deserialize(
                "<auth:open_dialog><hover:show_text:'Подсказка'>[Повторить]</hover></auth>",
                format
            )
            val json = MessageComponents.serializeJson(component)
            assertTrue(json.contains("pnauth:open_dialog"), "missing action for $format")
            assertTrue(json.contains("show_text"), "missing hover for $format")
        }
    }

    @Test
    fun `message renderer does not expose nested hover markup`() {
        val template = "<gray>Ошибка</gray> <auth:open_dialog>" +
            "<hover:show_text:'<gray>Подсказка</gray>'>[Повторить]</hover></auth>"
        val rendered = MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE).render(template)
        val json = MessageComponents.serializeJson(
            MessageComponents.deserialize(rendered, MessageFormat.MINI_MESSAGE)
        )

        assertTrue(json.contains("show_text"))
        assertTrue(json.contains("Подсказка"))
        assertTrue(!json.contains("hover:show_text"))
    }

    @Test
    fun `formatted error replacement is inserted as visible text without escaped tags`() {
        val formattedError = "<gradient:#a855f7:#38bdf8>Неверный пароль.</gradient>"
        val visibleError = MessageComponents.serializePlain(
            MessageComponents.deserialize(formattedError, MessageFormat.MINI_MESSAGE)
        )
        val rendered = MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE).render(
            "<red>{error}</red> <auth:open_dialog>[Повторить]</auth>",
            mapOf("error" to visibleError)
        )
        val json = MessageComponents.serializeJson(
            MessageComponents.deserialize(rendered, MessageFormat.MINI_MESSAGE)
        )

        assertTrue(json.contains("Неверный пароль."))
        assertTrue(!json.contains("gradient:#"))
        assertTrue(json.contains("pnauth:open_dialog"))
    }
}
