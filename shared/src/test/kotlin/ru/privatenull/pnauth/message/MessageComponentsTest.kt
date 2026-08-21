package ru.privatenull.pnauth.message

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageComponentsTest {
    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `format is explicit and is never guessed from angle brackets`() {
        val component = MessageComponents.deserialize("Use <password> here", MessageFormat.PLAIN)
        assertEquals("Use <password> here", plain.serialize(component))
    }

    @Test
    fun `legacy and mini message produce the same visible text`() {
        val legacy = MessageComponents.deserialize("&#ff00aaПривет &lмир", MessageFormat.LEGACY)
        val mini = MessageComponents.deserialize("<#ff00aa>Привет <bold>мир</bold>", MessageFormat.MINI_MESSAGE)
        assertEquals(plain.serialize(legacy), plain.serialize(mini))
    }

    @Test
    fun `malformed json degrades to harmless text`() {
        val component = MessageComponents.deserialize("{broken", MessageFormat.JSON)
        assertEquals("{broken", plain.serialize(component))
    }
}
