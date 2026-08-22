package ru.privatenull.pnauth.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.message.MessageFormat

class MessageFormatSerializerTest {
    private val serializer = MessageFormatSerializer()

    @Test
    fun `deserializes supported names and aliases`() {
        assertEquals(MessageFormat.MINI_MESSAGE, serializer.deserialize("mini-message"))
        assertEquals(MessageFormat.JSON, serializer.deserialize("JSON"))
    }

    @Test
    fun `falls back to the safe default for an invalid value`() {
        assertEquals(MessageFormat.MINI_MESSAGE, serializer.deserialize("something-broken"))
    }

    @Test
    fun `serializes enum as a stable yaml value`() {
        assertEquals("PLAIN", serializer.serialize(MessageFormat.PLAIN))
    }
}
