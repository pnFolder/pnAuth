package ru.privatenull.pnauth.configuration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ConfigDocumentTest {
    @Test
    fun `opens and saves yaml document`(@TempDir directory: Path) {
        val file = directory.resolve("config.yml")
        val document = ConfigDocument.open(file)
        document.node.node("answer").raw(42)
        document.save()

        val reloaded = ConfigDocument.open(file)
        assertEquals(42, reloaded.node.node("answer").getInt())
    }

    @Test
    fun `atomically replaces and backs up documents`(@TempDir directory: Path) {
        val file = directory.resolve("config.yml")
        val document = ConfigDocument.open(file)
        document.node.node("schema").raw(2)
        document.saveAtomically()

        val backup = document.backup()
        document.node.node("schema").raw(3)
        document.saveAtomically()

        assertEquals(3, ConfigDocument.open(file).node.node("schema").getInt())
        assertEquals(2, ConfigDocument.open(backup).node.node("schema").getInt())
        assertTrue(backup.fileName.toString().endsWith(".bak"))
    }
}
