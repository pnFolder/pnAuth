package ru.privatenull.pnauth.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.privatenull.pnauth.message.MessageFormat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class AuthConfigTest {
    @Test
    fun createsReadableDefaultConfiguration(@TempDir directory: Path) {
        val config = AuthConfig.load(
            directory.resolve("config.yml"),
            "jdbc:sqlite:" + directory.resolve("fallback.db")
        )

        assertEquals("ru", config.locale)
        assertTrue(config.storage.url.endsWith("auth.db"))
        assertTrue(config.security.isUsernameValid("Player_123"))
        assertFalse(config.security.isUsernameValid("bad name"))
        assertFalse(config.security.isPasswordValid("1234567"))
        assertTrue(config.proxy.requireServerAuth)
        val generated = Files.readString(directory.resolve("config.yml"))
        assertTrue(generated.contains("servers:"))
        assertTrue(generated.contains("security:"))
        assertTrue(generated.contains("Поддерживаемые значения: ru, en"))
        assertTrue(generated.contains("limbo:"))
        assertTrue(generated.contains("messages:"))
        assertTrue(generated.contains("processing-title:"))
        assertTrue(generated.contains("reopen-on-failure:"))
        assertEquals(ProcessingTitleSettings.Type.GRADIENT, config.processingTitle.animation.type)
        assertEquals(48, config.processingTitle.animation.frameCount)
        assertEquals(100L, config.processingTitle.timings.frameInterval.toMillis())
        assertFalse(config.features.dialogs.reopenOnFailure)
        assertEquals(MessageFormat.MINI_MESSAGE, config.messageFormat)
    }

    @Test
    fun configManagerCreatesAndUpdatesDocumentedConfiguration(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        val manager = PnAuthConfigManager(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))

        val created = manager.load()
        val generated = Files.readString(configFile)

        assertEquals("ru", created.locale)
        assertTrue(generated.contains("config-version"))
        assertTrue(generated.contains("Версия схемы"))

        Files.writeString(configFile, "locale: en\nmessages:\n  format: PLAIN\n")
        val updated = manager.load()

        assertEquals("en", updated.locale)
        assertEquals(MessageFormat.PLAIN, updated.messageFormat)
        assertTrue(Files.readString(configFile).contains("database:"))
    }

    @Test
    fun upgradesOlderSchemaOnceAndAddsNewTotpSettings(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, "config-version: 1\nlocale: en\n")

        PnAuthConfigManager(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db")).load()
        val upgraded = Files.readString(configFile)

        assertTrue(upgraded.contains("config-version: " + AuthConfig.CURRENT_SCHEMA_VERSION))
        assertTrue(upgraded.contains("setup-lifetime-seconds"))
        assertTrue(upgraded.contains("restore-on-same-ip"))
        assertEquals("config-version: 1\nlocale: en\n", Files.readString(configFile.resolveSibling("config.yml.bak")))
    }

    @Test
    fun repairsCurrentSchemaWhenRequiredFieldWasRemoved(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, """
            config-version: 3
            locale: en
            features:
              totp:
                setup-lifetime-seconds: 300
        """.trimIndent())

        PnAuthConfigManager(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db")).load()

        val upgraded = Files.readString(configFile)
        assertTrue(upgraded.contains("restore-on-same-ip"))
        assertTrue(Files.exists(configFile.resolveSibling("config.yml.bak")))
    }

    @Test
    fun readsCustomUsernameRule(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(
            configFile, """
            locale: en
            messages:
              format: MINI_MESSAGE
            database:
              type: SQLITE
              file: auth.db
            validation:
              username-pattern: '^player_[0-9]+$'
        """.trimIndent()
        )

        val config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))

        assertEquals("en", config.locale)
        assertEquals(MessageFormat.MINI_MESSAGE, config.messageFormat)
        assertTrue(config.security.isUsernameValid("player_42"))
        assertFalse(config.security.isUsernameValid("Player_42"))
    }

    @Test
    fun rejectsUnsupportedLocale(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, "locale: de\n")

        val error = assertThrows(IOException::class.java) {
            AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))
        }

        assertTrue(error.message?.contains("Supported locales: ru, en") == true)
    }

    @Test
    fun allowsDisablingAuthenticationReminders(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, "features:\n  session:\n    reminder-seconds: 0\n")

        val config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))

        assertTrue(config.features.reminderInterval.isZero)
    }

    @Test
    fun buildsDriverSpecificTlsDatabaseUrls(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, """
            database:
              type: MYSQL
              mysql:
                host: db.example.test
                port: 3306
                database: pnauth
                use-ssl: true
                server-timezone: UTC
        """.trimIndent())
        assertTrue(AuthConfig.load(configFile, "").storage.url.contains("sslMode=VERIFY_IDENTITY"))

        Files.writeString(configFile, """
            database:
              type: POSTGRESQL
              postgresql:
                host: db.example.test
                port: 5432
                database: pnauth
                use-ssl: true
        """.trimIndent())
        val postgresUrl = AuthConfig.load(configFile, "").storage.url
        assertTrue(postgresUrl.contains("sslmode=verify-full"))
        assertFalse(postgresUrl.contains("serverTimezone"))
    }

    @Test
    fun migratesLegacyForkedPicoLimboDownload(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(configFile, """
            limbo:
              download-base-url: "https://github.com/pnFolder/PicoLimbo/releases/download/v1.13.2-pn.2%2Bmc26.2/"
              download-sha-256: "701ad39c987e01edc659198d166e91d91a3182b8ae7df3bcc7c8366629089e13"
        """.trimIndent())

        val config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))

        assertEquals(LimboSettings.OFFICIAL_DOWNLOAD_BASE_URL, config.limbo.downloadBaseUrl)
        assertEquals(LimboSettings.OFFICIAL_DOWNLOAD_SHA256, config.limbo.downloadSha256)
        val migrated = Files.readString(configFile)
        assertTrue(migrated.contains("github.com/Quozul/PicoLimbo"))
        assertFalse(migrated.contains("github.com/pnFolder/PicoLimbo"))
    }
}
