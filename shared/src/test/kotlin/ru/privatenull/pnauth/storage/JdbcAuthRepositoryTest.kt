package ru.privatenull.pnauth.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.privatenull.pnauth.api.DialogPreference
import java.nio.file.Path
import java.util.UUID

class JdbcAuthRepositoryTest {
    @Test
    fun persistsAccountInSqlite(@TempDir temporaryDirectory: Path) {
        val database = temporaryDirectory.resolve("auth.db")
        val uniqueId = UUID.randomUUID()
        val record = AuthRecord(
            uniqueId,
            "steve",
            PasswordHash("salt", "hash", 120_000),
            1000L,
            null
        )

        JdbcAuthRepository("jdbc:sqlite:$database", "", "").use { repository ->
            assertTrue(repository.create(record))
            assertEquals(record, repository.findByUniqueId(uniqueId).orElseThrow())
            assertEquals(record, repository.findByUsername("STEVE").orElseThrow())
            repository.updateLastLogin(uniqueId, 2000L)
            assertEquals(2000L, repository.findByUniqueId(uniqueId).orElseThrow().lastLoginAt())
            repository.updateDialogPreference(uniqueId, DialogPreference.DISABLED)
            assertEquals(DialogPreference.DISABLED, repository.findByUniqueId(uniqueId).orElseThrow().dialogPreference())
        }
    }
}
