package ru.privatenull.pnauth.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class TotpKeyStoreTest {
    @Test
    fun createsAndReusesOneKey(@TempDir directory: Path) {
        val keyFile = directory.resolve("totp.key")
        val created = TotpKeyStore.loadOrCreate(keyFile)

        assertEquals(32, created.size)
        assertArrayEquals(created, TotpKeyStore.loadOrCreate(keyFile))
    }

    @Test
    fun rejectsMalformedExistingKey(@TempDir directory: Path) {
        val keyFile = directory.resolve("totp.key")
        Files.write(keyFile, ByteArray(10))

        assertThrows(IOException::class.java) { TotpKeyStore.loadOrCreate(keyFile) }
    }
}
