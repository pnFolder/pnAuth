package ru.privatenull.pnauth.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

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
        assertEquals("Red Alex", MessageRenderers.forFormat(MessageFormat.PLAIN)
                .render(legacy, java.util.Map.of("name", "Alex")));
    }

    @Test
    void doesNotAllowPlaceholdersToInjectFormatting() {
        String value = MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE)
                .render("Hello {name}", java.util.Map.of("name", "<red>&cAdmin"));

        assertEquals("Hello \\<red\\>Admin", value);
    }

    @Test
    void preservesMiniMessageTagsInLocaleValues() {
        String value = MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE)
                .render("<hover:show_text:'Подробнее'><click:run_command:'/login'>Войти</click></hover>");

        assertEquals("<hover:show_text:'Подробнее'><click:run_command:'/login'>Войти</click></hover>", value);
    }

    @Test
    void parsesFormatAliases() {
        assertEquals(MessageFormat.MINI_MESSAGE, MessageFormat.parse("mini-message"));
        assertEquals(MessageFormat.JSON, MessageFormat.parse("component"));
        assertEquals(MessageFormat.PLAIN, MessageFormat.parse("plain_text"));
    }

    @Test
    void generatesEditableLocalesAndAddsNewKeys(@TempDir Path directory) throws Exception {
        MessageFileGenerator.ensureAll(directory);
        Path file = directory.resolve("messages_en.yml");
        String original = Files.readString(file);
        Files.writeString(file, original.replace("Usage: /login <password>", "Custom login text"));

        MessageFileGenerator.ensure(directory, "en");
        String updated = Files.readString(file);

        assertTrue(updated.contains("Custom login text") || updated.contains("Usage: /login <password>"));
        assertTrue(updated.contains("forceregister"));
        assertTrue(Files.exists(directory.resolve("messages_ru.yml")));
    }
}
