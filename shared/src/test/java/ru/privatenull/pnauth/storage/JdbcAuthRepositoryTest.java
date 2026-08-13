package ru.privatenull.pnauth.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.privatenull.pnauth.api.DialogPreference;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAuthRepositoryTest {
    @Test
    void persistsAccountInSqlite(@TempDir Path temporaryDirectory) {
        Path database = temporaryDirectory.resolve("auth.db");
        UUID uniqueId = UUID.randomUUID();
        AuthRecord record = new AuthRecord(
                uniqueId,
                "steve",
                new PasswordHash("salt", "hash", 120_000),
                1000L,
                null
        );

        try (JdbcAuthRepository repository = new JdbcAuthRepository("jdbc:sqlite:" + database, "", "")) {
            assertTrue(repository.create(record));
            assertEquals(record, repository.findByUniqueId(uniqueId).orElseThrow());
            assertEquals(record, repository.findByUsername("STEVE").orElseThrow());
            repository.updateLastLogin(uniqueId, 2000L);
            assertEquals(2000L, repository.findByUniqueId(uniqueId).orElseThrow().lastLoginAt());
            repository.updateDialogPreference(uniqueId, DialogPreference.DISABLED);
            assertEquals(DialogPreference.DISABLED, repository.findByUniqueId(uniqueId).orElseThrow().dialogPreference());
        }
    }
}
