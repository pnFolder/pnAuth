package ru.privatenull.pnauth.velocity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.message.MessageFormat

class VelocityMessagesTest {
    @Test
    fun preservesJsonClickAndHoverEvents() {
        val component = VelocityMessages.component(
            """
            {"text":"Open","clickEvent":{"action":"run_command","value":"/login password"},"hoverEvent":{"action":"show_text","contents":{"text":"Authenticate"}}}
            """.trimIndent(),
            MessageFormat.JSON
        )

        assertEquals("/login password", component.clickEvent()?.value())
        assertNotNull(component.hoverEvent())
    }
}
