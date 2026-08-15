package ru.privatenull.pnauth.bungee

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.message.MessageFormat

class BungeeMessagesTest {
    @Test
    fun preservesJsonClickAndHoverEvents() {
        val components = BungeeMessages.components(
            """
            {"text":"Open","clickEvent":{"action":"run_command","value":"/login password"},"hoverEvent":{"action":"show_text","contents":{"text":"Authenticate"}}}
            """.trimIndent(),
            MessageFormat.JSON
        )

        assertEquals(1, components.size)
        assertEquals("/login password", components[0].clickEvent.value)
        assertNotNull(components[0].hoverEvent)
    }
}
