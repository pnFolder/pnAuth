package ru.privatenull.pnauth.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogResponseTest {
    @Test
    fun readsBooleanAndNumericNbtScalars() {
        val response = DialogResponse(
            "test:submit",
            mapOf("enabled" to 1.toByte(), "amount" to 42.5f), false
        )

        assertEquals(true, response.bool("enabled").orElseThrow())
        assertEquals(42.5f, response.number("amount").orElseThrow().toFloat())
    }

    @Test
    fun readsNumericNbtScalarAsTextInput() {
        val response = DialogResponse("pnauth:login", mapOf("password" to 123456), false)

        assertEquals("123456", response.string("password").orElseThrow())
    }

    @Test
    fun doesNotInventTextForStructureWithoutScalarValues() {
        val response = DialogResponse(
            "test:submit",
            mapOf("value" to mapOf("first" to mapOf<String, Any>(), "second" to emptyList<Any>())), false
        )

        assertTrue(response.string("value").isEmpty)
    }

    @Test
    fun unwrapsSingleScalarFromPacketEventsCompound() {
        val response = DialogResponse(
            "pnauth:login",
            mapOf("password" to mapOf("value" to "secret")), false
        )

        assertEquals("secret", response.string("password").orElseThrow())
    }

    @Test
    fun prefersValueFromTypedPacketEventsCompound() {
        val response = DialogResponse(
            "pnauth:login",
            mapOf("password" to mapOf("type" to "minecraft:string", "value" to "example-secret")), false
        )

        assertEquals("example-secret", response.string("password").orElseThrow())
    }

    @Test
    fun unwrapsPacketEventsListWrapper() {
        val response = DialogResponse(
            "pnauth:login",
            mapOf("password" to listOf(mapOf("input" to "example-secret"))), false
        )

        assertEquals("example-secret", response.string("password").orElseThrow())
    }

    @Test
    fun ignoresUnknownMetadataAroundTextValue() {
        val response = DialogResponse(
            "pnauth:login", mapOf(
                "password" to mapOf(
                    "codec" to "minecraft:string", "payload" to mapOf("raw" to "example-secret"), "flags" to false
                )
            ), false
        )

        assertEquals("example-secret", response.string("password").orElseThrow())
    }
}
