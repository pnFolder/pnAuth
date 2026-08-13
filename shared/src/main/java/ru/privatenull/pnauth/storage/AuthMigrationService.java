package ru.privatenull.pnauth.storage;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Imports common auth schemas without depending on the source plugin. */
public final class AuthMigrationService implements AutoCloseable {
    private final AuthRepository target;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pnauth-migration");
        thread.setDaemon(true);
        return thread;
    });

    public AuthMigrationService(AuthRepository target) {
        this.target = target;
    }

    public CompletableFuture<Integer> migrate(
            Source source,
            String jdbcUrl,
            String username,
            String password
    ) {
        return CompletableFuture.supplyAsync(() -> run(source, jdbcUrl, username, password), executor);
    }

    private int run(Source source, String url, String username, String password) {
        int migrated = 0;
        try (Connection connection = username == null || username.isBlank()
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, username, password == null ? "" : password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(source.query)) {
            while (result.next()) {
                AuthRecord record = source.read(result);
                if (target.create(record)) migrated++;
            }
            return migrated;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not migrate authentication database", exception);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public enum Source {
        TIAUTH("SELECT username, realName, password, premium, lastIp, regIp, lastLogin, regDate FROM auth_users") {
            @Override
            AuthRecord read(ResultSet result) throws SQLException {
                return record(result.getString("username"), result.getString("realName"), result.getString("password"),
                        result.getBoolean("premium"), result.getString("regIp"), result.getString("lastIp"),
                        result.getLong("regDate"), result.getLong("lastLogin"));
            }
        },
        AUTHME("SELECT username, realname, password, regip, lastlogin, regdate FROM authme") {
            @Override
            AuthRecord read(ResultSet result) throws SQLException {
                return record(result.getString("username"), result.getString("realname"), result.getString("password"),
                        false, result.getString("regip"), result.getString("regip"), timestamp(result, "regdate"),
                        timestamp(result, "lastlogin"));
            }
        },
        MCAUTH("SELECT player_id, player_name, password_hash, last_ip, last_session_start FROM mc_auth_accounts") {
            @Override
            AuthRecord read(ResultSet result) throws SQLException {
                return record(result.getString("player_id"), result.getString("player_name"),
                        result.getString("password_hash"), false, result.getString("last_ip"), result.getString("last_ip"),
                        result.getLong("last_session_start"), result.getLong("last_session_start"));
            }
        },
        LIMBOAUTH("SELECT LOWERCASENICKNAME, NICKNAME, HASH, PREMIUMUUID, LOGINIP, IP, LOGINDATE, REGDATE FROM AUTH") {
            @Override
            AuthRecord read(ResultSet result) throws SQLException {
                String premiumUuid = result.getString("PREMIUMUUID");
                return record(result.getString("LOWERCASENICKNAME"), result.getString("NICKNAME"), result.getString("HASH"),
                        premiumUuid != null && !premiumUuid.isBlank(), result.getString("IP"), result.getString("LOGINIP"),
                        result.getLong("REGDATE"), result.getLong("LOGINDATE"));
            }
        },
        NLOGIN("SELECT last_name, password, last_ip, last_seen, creation_date FROM nlogin") {
            @Override
            AuthRecord read(ResultSet result) throws SQLException {
                return record(result.getString("last_name"), result.getString("last_name"), result.getString("password"),
                        false, result.getString("last_ip"), result.getString("last_ip"),
                        timestamp(result, "creation_date"), timestamp(result, "last_seen"));
            }
        };

        private final String query;

        Source(String query) {
            this.query = query;
        }

        abstract AuthRecord read(ResultSet result) throws SQLException;

        private static AuthRecord record(
                String username,
                String realName,
                String password,
                boolean premium,
                String registeredIp,
                String lastIp,
                long registeredAt,
                long lastLoginAt
        ) {
            String normalized = username.toLowerCase(Locale.ROOT);
            String algorithm = password != null && password.startsWith("$2") ? "BCRYPT"
                    : password != null && password.startsWith("$argon2") ? "ARGON2"
                    : password != null && password.startsWith("$SHA$") ? "SHA256_AUTHME"
                    : "SHA256";
            return new AuthRecord(
                    UUID.nameUUIDFromBytes(("OfflinePlayer:" + realName).getBytes(StandardCharsets.UTF_8)),
                    normalized,
                    realName,
                    PasswordHash.legacy(algorithm, password),
                    registeredAt,
                    lastLoginAt,
                    premium,
                    registeredIp,
                    lastIp,
                    null
            );
        }

        private static long timestamp(ResultSet result, String column) throws SQLException {
            java.sql.Timestamp timestamp = result.getTimestamp(column);
            return timestamp == null ? System.currentTimeMillis() : timestamp.getTime();
        }
    }
}
