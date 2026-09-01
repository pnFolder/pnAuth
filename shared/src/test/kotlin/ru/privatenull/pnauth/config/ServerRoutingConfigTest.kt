package ru.privatenull.pnauth.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ServerRoutingConfigTest {
    @Test
    fun generatesStructuredServerGroups(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")

        val config = AuthConfig.load(
            configFile,
            "jdbc:sqlite:" + directory.resolve("fallback.db")
        )

        assertEquals(listOf("auth"), config.proxy.authServers)
        assertEquals(listOf("hub"), config.proxy.backendServers)
        assertEquals(100, config.proxy.serverLimits["auth"])
        assertEquals(200, config.proxy.serverLimits["hub"])

        val generated = Files.readString(configFile)
        assertTrue(generated.contains("auth:"))
        assertTrue(generated.contains("backend:"))
        assertTrue(generated.contains("server:"))
        assertTrue(generated.contains("online:"))
        assertFalse(generated.contains("type: SERVER"))
        assertFalse(generated.contains("type: LIMBO"))
        assertFalse(generated.contains("auth-server:"))
        assertFalse(generated.contains("backend-server:"))
    }

    @Test
    fun readsMultipleAuthAndBackendTargets(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        val currentConfig = """
            config-version: 13
            servers:
              auth:
                - server: auth-1
                  online: 80
                - server: auth-2
                  online: 120
              backend:
                - server: lobby-1
                  online: 200
                - server: lobby-2
                  online: 250
              balancer-mode: LOWEST_LOAD_PERCENT
              require-auth-before-server: true
            """.trimIndent()
        Files.writeString(configFile, currentConfig)

        val config = AuthConfig.load(
            configFile,
            "jdbc:sqlite:" + directory.resolve("fallback.db")
        )

        assertEquals(listOf("auth-1", "auth-2"), config.proxy.authServers)
        assertEquals(listOf("lobby-1", "lobby-2"), config.proxy.backendServers)
        assertEquals(80, config.proxy.serverLimits["auth-1"])
        assertEquals(120, config.proxy.serverLimits["auth-2"])
        assertEquals(200, config.proxy.serverLimits["lobby-1"])
        assertEquals(250, config.proxy.serverLimits["lobby-2"])
        assertTrue(config.proxy.isAuthServer("AUTH-2"))
        assertTrue(config.proxy.isBackendServer("Lobby-2"))
        assertEquals(currentConfig, Files.readString(configFile))
        assertFalse(Files.exists(configFile.resolveSibling("config.yml.bak")))
    }

    @Test
    fun migratesLegacyV12ServerFieldsWithoutLosingTargets(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        val legacy = """
            config-version: 12
            servers:
              auth-server: auth-main
              auth-servers:
                - auth-a
                - auth-b
              backend-server: hub-main
              backend-servers:
                - lobby-a
                - lobby-b
              max-players-per-server: 150
              server-limits:
                auth-a: 75
                lobby-b: 300
              balancer-mode: LEAST_PLAYERS
              require-auth-before-server: true
        """.trimIndent()
        Files.writeString(configFile, legacy)

        val config = AuthConfig.load(
            configFile,
            "jdbc:sqlite:" + directory.resolve("fallback.db")
        )

        assertEquals(listOf("auth-a", "auth-b"), config.proxy.authServers)
        assertEquals(listOf("lobby-a", "lobby-b"), config.proxy.backendServers)
        assertEquals(75, config.proxy.serverLimits["auth-a"])
        assertEquals(150, config.proxy.serverLimits["auth-b"])
        assertEquals(150, config.proxy.serverLimits["lobby-a"])
        assertEquals(300, config.proxy.serverLimits["lobby-b"])

        assertEquals(legacy, Files.readString(configFile))
        assertFalse(Files.exists(configFile.resolveSibling("config.yml.bak")))
    }

    @Test
    fun detectsEnabledLimboRouteByConfiguredName(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(
            configFile,
            """
            config-version: 13
            servers:
              auth:
                - server: auth
                  online: 100
                - server: auth-2
                  online: 120
              backend:
                - server: lobby
                  online: 200
            limbo:
              enabled: true
              server-name: auth
            """.trimIndent()
        )

        val config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))

        assertEquals(ServerTargetType.LIMBO, config.proxy.serverType("auth"))
        assertEquals(ServerTargetType.SERVER, config.proxy.serverType("auth-2"))
        assertEquals(ServerTargetType.SERVER, config.proxy.serverType("lobby"))
    }

    @Test
    fun acceptsOldTypeFieldsButLeavesThemUntouched(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        val oldConfig = """
            config-version: 13
            servers:
              auth:
                - server: auth
                  online: 100
                  type: LIMBO
              backend:
                - server: lobby
                  online: 200
                  type: SERVER
            limbo:
              enabled: true
              server-name: auth
            """.trimIndent()
        Files.writeString(configFile, oldConfig)

        val config = AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))

        assertEquals(ServerTargetType.LIMBO, config.proxy.serverType("auth"))
        assertEquals(ServerTargetType.SERVER, config.proxy.serverType("lobby"))
        assertEquals(oldConfig, Files.readString(configFile))
    }

    @Test
    fun rejectsSameServerInAuthAndBackendBeforeOnlineLimitConflict(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(
            configFile,
            """
            config-version: 13
            servers:
              auth:
                - server: lobby
                  online: 100
              backend:
                - server: lobby
                  online: 200
            """.trimIndent()
        )

        val error = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) {
            AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))
        }
        assertTrue(error.message!!.contains("cannot be both AUTH"))
    }

    @Test
    fun rejectsBackendNameThatConflictsWithEnabledLimbo(@TempDir directory: Path) {
        val configFile = directory.resolve("config.yml")
        Files.writeString(
            configFile,
            """
            config-version: 13
            servers:
              auth:
                - server: auth-real
                  online: 100
              backend:
                - server: auth
                  online: 100
            limbo:
              enabled: true
              server-name: auth
            """.trimIndent()
        )

        val error = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) {
            AuthConfig.load(configFile, "jdbc:sqlite:" + directory.resolve("fallback.db"))
        }
        assertTrue(error.message!!.contains("conflicts with enabled Limbo"))
    }

    @Test
    fun keepsLegacyProxySettingsConstructorForInternalCompatibility() {
        val settings = ProxySettings(true, "auth", "hub", emptyMap())

        assertEquals(listOf("auth"), settings.authServers)
        assertEquals(listOf("hub"), settings.backendServers)
    }
}
