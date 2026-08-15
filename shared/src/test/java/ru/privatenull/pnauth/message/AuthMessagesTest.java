package ru.privatenull.pnauth.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthMessagesTest {
    @Test
    void loadsRussianAndEnglishBundles() throws Exception {
        assertTrue(AuthMessages.load("ru").text("prompt.unregistered").contains("Аккаунт"));
        assertEquals("Authenticate first.", AuthMessages.load("en").text("access.blocked"));
    }

    @Test
    void rendersSupportedMessageFormats() {
        String legacy = "&cRed {name}&r";
        assertEquals(legacy, MessageRenderers.forFormat(MessageFormat.LEGACY).render(legacy));
        assertEquals("<red>Red Alex</red>", MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE)
                .render(legacy, java.util.Map.of("name", "Alex")));
        assertEquals("[{\"text\":\"Red Alex\",\"color\":\"red\"}]",
                MessageRenderers.forFormat(MessageFormat.JSON).render(legacy, java.util.Map.of("name", "Alex")));
        assertEquals("Red Alex", MessageRenderers.forFormat(MessageFormat.PLAIN).render(legacy, java.util.Map.of("name", "Alex")));
    }

    @Test
    void convertsAllLegacyDecorationsForMiniMessageAndJson() {
        String legacy = "&cRed &lBold &oItalic &nUnderlined &mStruck &kMagic&r plain";

        String mini = MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE).render(legacy);
        assertTrue(mini.contains("<red>Red </red>"));
        assertTrue(mini.contains("<bold>Bold "));
        assertTrue(mini.contains("<italic>Italic "));
        assertTrue(mini.contains("<underlined>Underlined "));
        assertTrue(mini.contains("<strikethrough>Struck "));
        assertTrue(mini.contains("<obfuscated>Magic</obfuscated>"));
        assertFalse(mini.contains("&l"));
        assertTrue(mini.endsWith(" plain"));

        String json = MessageRenderers.forFormat(MessageFormat.JSON).render(legacy);
        assertTrue(json.contains("\"color\":\"red\""));
        assertTrue(json.contains("\"bold\":true"));
        assertTrue(json.contains("\"italic\":true"));
        assertTrue(json.contains("\"underlined\":true"));
        assertTrue(json.contains("\"strikethrough\":true"));
        assertTrue(json.contains("\"obfuscated\":true"));
        assertFalse(json.contains("&l"));
    }

    @Test
    void preservesJsonComponentTemplatesAndEscapesReplacementValues() throws Exception {
        String template = """
                {"text":"Open {name}","clickEvent":{"action":"run_command","value":"/msg {name}"},"hoverEvent":{"action":"show_text","contents":{"text":"Details"}}}
                """;

        String rendered = MessageRenderers.forFormat(MessageFormat.JSON)
                .render(template, java.util.Map.of("name", "<red>&l\"Alex\""));
        JsonNode component = new ObjectMapper().readTree(rendered);

        assertEquals("Open <red>\"Alex\"", component.path("text").asText());
        assertEquals("/msg <red>\"Alex\"", component.path("clickEvent").path("value").asText());
        assertEquals("Details", component.path("hoverEvent").path("contents").path("text").asText());
        assertFalse(rendered.contains("&l"));
    }

    @Test
    void doesNotAllowPlaceholdersToInjectFormatting() {
        assertEquals("Hello \\<red\\>Admin", MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE)
                .render("Hello {name}", java.util.Map.of("name", "<red>&cAdmin")));
    }

    @Test
    void preservesMiniMessageTagsInLocaleValues() {
        String value = "<hover:show_text:'Подробнее'><click:run_command:'/login'>Войти</click></hover>";
        assertEquals(value, MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE).render(value));
    }

    @Test
    void parsesFormatAliases() {
        assertEquals(MessageFormat.MINI_MESSAGE, MessageFormat.parse("mini-message"));
        assertEquals(MessageFormat.JSON, MessageFormat.parse("component"));
        assertEquals(MessageFormat.PLAIN, MessageFormat.parse("plain_text"));
    }

    @Test
    void preservesEditableLocalesAndUsesBuiltInFallbacksForNewKeys(@TempDir Path directory) throws Exception {
        MessageFileGenerator.ensureAll(directory);
        Path file = directory.resolve("messages_en.yml");
        Files.writeString(file, "# Keep this administrator comment\n" + Files.readString(file)
                .replace("Usage: /login <password>", "Custom login text")
                .replaceAll("(?m)^  \\\"?register-single\\\"?:.*(?:\\R|$)", ""));
        MessageFileGenerator.ensure(directory, "en");
        String updated = Files.readString(file);
        assertTrue(updated.contains("# Keep this administrator comment"));
        assertTrue(updated.contains("Custom login text"));
        assertTrue(!updated.contains("register-single"));
        assertEquals("Usage: /register <password>",
                AuthMessages.load(directory, "en", MessageFormat.LEGACY).text("usage.register-single"));
        assertTrue(Files.exists(directory.resolve("messages_ru.yml")));
    }
}
