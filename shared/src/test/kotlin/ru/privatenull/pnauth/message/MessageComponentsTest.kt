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

        assertTrue(json.contains("/_pnauthui open"))
        assertTrue(json.contains("show_text"))
        assertTrue(json.contains("Повторить"))
    }

    @Test
    fun `auth action remains available when ordinary messages use legacy`() {
        val component = MessageComponents.deserialize(
            "<auth:open_dialog>[Повторить]</auth>",
            MessageFormat.LEGACY
        )

        assertTrue(MessageComponents.serializeJson(component).contains("/_pnauthui open"))
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
}
