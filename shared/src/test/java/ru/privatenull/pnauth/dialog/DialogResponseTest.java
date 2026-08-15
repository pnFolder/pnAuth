package ru.privatenull.pnauth.dialog;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogResponseTest {
    @Test
    void readsBooleanAndNumericNbtScalars() {
        DialogResponse response = new DialogResponse("test:submit",
                Map.of("enabled", (byte) 1, "amount", 42.5F), false);

        assertEquals(true, response.bool("enabled").orElseThrow());
        assertEquals(42.5F, response.number("amount").orElseThrow().floatValue());
    }

    @Test
    void readsNumericNbtScalarAsTextInput() {
        DialogResponse response = new DialogResponse("pnauth:login", Map.of("password", 123456), false);

        assertEquals("123456", response.string("password").orElseThrow());
    }

    @Test
    void doesNotInventTextForStructureWithoutScalarValues() {
        DialogResponse response = new DialogResponse("test:submit",
                Map.of("value", Map.of("first", Map.of(), "second", java.util.List.of())), false);

        assertTrue(response.string("value").isEmpty());
    }

    @Test
    void unwrapsSingleScalarFromPacketEventsCompound() {
        DialogResponse response = new DialogResponse("pnauth:login",
                Map.of("password", Map.of("value", "secret")), false);

        assertEquals("secret", response.string("password").orElseThrow());
    }

    @Test
    void prefersValueFromTypedPacketEventsCompound() {
        DialogResponse response = new DialogResponse("pnauth:login",
                Map.of("password", Map.of("type", "minecraft:string", "value", "example-secret")), false);

        assertEquals("example-secret", response.string("password").orElseThrow());
    }

    @Test
    void unwrapsPacketEventsListWrapper() {
        DialogResponse response = new DialogResponse("pnauth:login",
                Map.of("password", java.util.List.of(Map.of("input", "example-secret"))), false);

        assertEquals("example-secret", response.string("password").orElseThrow());
    }

    @Test
    void ignoresUnknownMetadataAroundTextValue() {
        DialogResponse response = new DialogResponse("pnauth:login", Map.of("password", Map.of(
                "codec", "minecraft:string", "payload", Map.of("raw", "example-secret"), "flags", false
        )), false);

        assertEquals("example-secret", response.string("password").orElseThrow());
    }
}
