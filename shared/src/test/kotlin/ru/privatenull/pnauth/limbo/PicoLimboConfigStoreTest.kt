package ru.privatenull.pnauth.limbo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PicoLimboConfigStoreTest {
    @Test
    fun disablesForwardingForEmbeddedLoopbackServer(@TempDir directory: Path) {
        val config = directory.resolve("config.toml")
        Files.writeString(config, """
            bind = "127.0.0.1:25566"

            [forwarding]
            method = "LEGACY"
            secret = "old-secret"

            [world]
            dimension = "overworld"
        """.trimIndent())

        val store = PicoLimboConfigStore()
        store.prepareEmbedded(config)
        val parsed = store.load(config)

        assertEquals("NONE", parsed.forwarding.method)
        assertEquals("", parsed.forwarding.secret)
        val migrated = Files.readString(config)
        assertTrue(migrated.contains("[world]"))
        assertTrue(migrated.contains("forwarding.method = 'NONE'"))
        store.prepareEmbedded(config)
        assertEquals(migrated, Files.readString(config))
    }

    @Test
    fun removesConflictingDottedAndTableForwarding(@TempDir directory: Path) {
        val config = directory.resolve("config.toml")
        Files.writeString(config, """
            bind = '127.0.0.1:25566'
            forwarding.method = 'LEGACY'
            forwarding.secret = ''

            [forwarding]
            method = "NONE"
            secret = ""
        """.trimIndent())

        val store = PicoLimboConfigStore()
        store.prepareEmbedded(config)

        val parsed = store.load(config)
        assertEquals("NONE", parsed.forwarding.method)
        assertEquals(1, Files.readString(config).split("forwarding.method").size - 1)
    }

    @Test
    fun createsAndSynchronizesEmbeddedEndpoint(@TempDir directory: Path) {
        val config = directory.resolve("config.toml")
        val store = PicoLimboConfigStore()

        store.prepareEmbedded(config, "127.0.0.1", 25577)
        val created = store.load(config)
        assertEquals("127.0.0.1", created.endpoint().host)
        assertEquals(25577, created.endpoint().port)
        assertEquals("NONE", created.forwarding.method)

        Files.writeString(config, Files.readString(config) + "\n[world]\ndimension = \"overworld\"\n")
        store.prepareEmbedded(config, "127.0.0.1", 25578)
        val updated = store.load(config)
        assertEquals(25578, updated.endpoint().port)
        assertTrue(Files.readString(config).contains("[world]"))
    }
}
