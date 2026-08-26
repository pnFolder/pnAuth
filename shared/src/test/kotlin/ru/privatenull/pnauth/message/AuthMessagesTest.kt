package ru.privatenull.pnauth.message

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AuthMessagesTest {
    @Test
    fun loadsRussianAndEnglishBundles() {
        assertTrue(AuthMessages.load("ru").text("prompt.unregistered").contains("Аккаунт"))
        assertTrue(AuthMessages.load("en").text("access.blocked").contains("Authenticate first."))
    }

    @Test
    fun defaultCommandUsageIsAStyledComponentInsteadOfEscapedMarkup() {
        val messages = AuthMessages.load("ru", MessageFormat.MINI_MESSAGE)
        val rendered = messages.text("usage.register")
        val component = MessageComponents.deserialize(rendered, MessageFormat.MINI_MESSAGE)
        val plain = MessageComponents.serializePlain(component)
        val json = MessageComponents.serializeJson(component)

        assertTrue(plain.contains("pnAuth"))
        assertTrue(plain.contains("Использование: /register"))
        assertFalse(plain.contains("<dark_gray>"))
        assertTrue(json.contains("gradient") || json.contains("color"))
    }

    @Test
    fun rendersSupportedMessageFormats() {
        val legacy = "&cRed {name}&r"
        assertEquals(legacy, MessageRenderers.forFormat(MessageFormat.LEGACY).render(legacy))
        assertEquals(
            "<red>Red Alex</red>", MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE)
                .render(legacy, mapOf("name" to "Alex"))
        )
        assertEquals(
            "[{\"text\":\"Red Alex\",\"color\":\"red\"}]",
            MessageRenderers.forFormat(MessageFormat.JSON).render(legacy, mapOf("name" to "Alex"))
        )
        assertEquals("Red Alex", MessageRenderers.forFormat(MessageFormat.PLAIN).render(legacy, mapOf("name" to "Alex")))
    }

    @Test
    fun convertsAllLegacyDecorationsForMiniMessageAndJson() {
        val legacy = "&cRed &lBold &oItalic &nUnderlined &mStruck &kMagic&r plain"

        val mini = MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE).render(legacy)
        assertTrue(mini.contains("<red>Red </red>"))
        assertTrue(mini.contains("<bold>Bold "))
        assertTrue(mini.contains("<italic>Italic "))
        assertTrue(mini.contains("<underlined>Underlined "))
        assertTrue(mini.contains("<strikethrough>Struck "))
        assertTrue(mini.contains("<obfuscated>Magic</obfuscated>"))
        assertFalse(mini.contains("&l"))
        assertTrue(mini.endsWith(" plain"))

        val json = MessageRenderers.forFormat(MessageFormat.JSON).render(legacy)
        assertTrue(json.contains("\"color\":\"red\""))
        assertTrue(json.contains("\"bold\":true"))
        assertTrue(json.contains("\"italic\":true"))
        assertTrue(json.contains("\"underlined\":true"))
        assertTrue(json.contains("\"strikethrough\":true"))
        assertTrue(json.contains("\"obfuscated\":true"))
        assertFalse(json.contains("&l"))
    }

    @Test
    fun preservesJsonComponentTemplatesAndEscapesReplacementValues() {
        val template = """
            {"text":"Open {name}","clickEvent":{"action":"run_command","value":"/msg {name}"},"hoverEvent":{"action":"show_text","contents":{"text":"Details"}}}
        """.trimIndent()

        val rendered = MessageRenderers.forFormat(MessageFormat.JSON)
            .render(template, mapOf("name" to "<red>&l\"Alex\""))
        val component = ObjectMapper().readTree(rendered)

        assertEquals("Open <red>\"Alex\"", component.path("text").asText())
        assertEquals("/msg <red>\"Alex\"", component.path("clickEvent").path("value").asText())
        assertEquals("Details", component.path("hoverEvent").path("contents").path("text").asText())
        assertFalse(rendered.contains("&l"))
    }

    @Test
    fun doesNotAllowPlaceholdersToInjectFormatting() {
        assertEquals(
            "Hello \\<red\\>Admin", MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE)
                .render("Hello {name}", mapOf("name" to "<red>&cAdmin"))
        )
    }

    @Test
    fun preservesMiniMessageTagsInLocaleValues() {
        val value = "<hover:show_text:'Подробнее'><click:run_command:'/login'>Войти</click></hover>"
        assertEquals(value, MessageRenderers.forFormat(MessageFormat.MINI_MESSAGE).render(value))
    }

    @Test
    fun parsesFormatAliases() {
        assertEquals(MessageFormat.MINI_MESSAGE, MessageFormat.parse("mini-message"))
        assertEquals(MessageFormat.JSON, MessageFormat.parse("component"))
        assertEquals(MessageFormat.PLAIN, MessageFormat.parse("plain_text"))
    }

    @Test
    fun preservesEditableLocalesAndUsesBuiltInFallbacksForNewKeys(@TempDir directory: Path) {
        MessageFileGenerator.ensureAll(directory)
        val file = directory.resolve("messages_en.yml")
        Files.writeString(
            file, "# Keep this administrator comment\n" + Files.readString(file)
                .replace("Usage: /login <password>", "Custom login text")
                .replace("(?m)^  \"?register-single\"?:.*(?:\\R|$)".toRegex(), "")
        )
        MessageFileGenerator.ensure(directory, "en")
        val updated = Files.readString(file)
        assertTrue(updated.contains("# Keep this administrator comment"))
        assertTrue(updated.contains("Custom login text"))
        assertFalse(updated.contains("register-single"))
        assertTrue(
            AuthMessages.load(directory, "en", MessageFormat.LEGACY)
                .text("usage.register-single").contains("Usage: /register <password>")
        )
        assertTrue(Files.exists(directory.resolve("messages_ru.yml")))
    }

    @Test
    fun combinesLegacyDialogFieldsWithoutLosingCustomText(@TempDir directory: Path) {
        val file = directory.resolve("messages_ru.yml")
        Files.writeString(
            file,
            "dialog:\n  error: '&cСвоя ошибка: {error}'\n  retry: '&d[Ещё раз]'\n" +
                "  retry_hover: '&7Снова открыть форму'\n"
        )

        MessageFileGenerator.ensure(directory, "ru")

        val migrated = Files.readString(file)
        assertTrue(migrated.contains("Своя ошибка"))
        assertTrue(migrated.contains("Ещё раз"))
        assertTrue(migrated.contains("<auth:open_dialog>"))
        assertFalse(migrated.contains("retry_hover"))
        assertTrue(Files.exists(directory.resolve("messages_ru.yml.bak")))
        val rendered = AuthMessages.load(directory, "ru", MessageFormat.MINI_MESSAGE)
            .text("dialog.error", mapOf("error" to "Неверный пароль"))
        assertTrue(rendered.contains("Снова открыть форму"))
    }
}
