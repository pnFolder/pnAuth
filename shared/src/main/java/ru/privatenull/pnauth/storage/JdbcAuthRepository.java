package ru.privatenull.pnauth.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.privatenull.pnauth.api.DialogPreference;

public final class JdbcAuthRepository implements AuthRepository {
    private final String url;
    private final String username;
    private final String password;

    public JdbcAuthRepository(String url, String username, String password) {
        this.url = url;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        loadDriver(url);
        initialize();
    }

    @Override
    public Optional<AuthRecord> findByUniqueId(UUID uniqueId) {
        return find("SELECT uuid, username, real_name, algorithm, salt, password_hash, iterations, registered_at, "
                + "last_login_at, premium, registered_ip, last_ip, totp_secret, dialog_preference "
                + "FROM pnauth_users WHERE uuid = ?", uniqueId.toString());
    }

    @Override
    public Optional<AuthRecord> findByUsername(String username) {
        return find("SELECT uuid, username, real_name, algorithm, salt, password_hash, iterations, registered_at, "
                + "last_login_at, premium, registered_ip, last_ip, totp_secret, dialog_preference "
                + "FROM pnauth_users WHERE username = ?", username.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean create(AuthRecord record) {
        String sql = "INSERT INTO pnauth_users "
                + "(uuid, username, real_name, algorithm, salt, password_hash, iterations, registered_at, "
                + "last_login_at, premium, registered_ip, last_ip, totp_secret, dialog_preference) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.uniqueId().toString());
            statement.setString(2, record.username());
            statement.setString(3, record.realName());
            statement.setString(4, record.passwordHash().algorithm());
            statement.setString(5, record.passwordHash().salt());
            statement.setString(6, record.passwordHash().hash());
            statement.setInt(7, record.passwordHash().iterations());
            statement.setLong(8, record.registeredAt());
            if (record.lastLoginAt() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, record.lastLoginAt());
            }
            statement.setBoolean(10, record.premium());
            statement.setString(11, record.registeredIp());
            statement.setString(12, record.lastIp());
            statement.setString(13, record.totpSecret());
            statement.setString(14, record.dialogPreference().name());
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                return false;
            }
            throw new IllegalStateException("Could not create auth account", exception);
        }
    }

    @Override
    public boolean updateUsername(UUID uniqueId, String username) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE pnauth_users SET username = ? WHERE uuid = ?")) {
            statement.setString(1, username.toLowerCase(Locale.ROOT));
            statement.setString(2, uniqueId.toString());
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            if (isConstraintViolation(exception)) {
                return false;
            }
            throw new IllegalStateException("Could not update auth username", exception);
        }
    }

    @Override
    public void updateLastLogin(UUID uniqueId, long timestamp) {
        executeUpdate("UPDATE pnauth_users SET last_login_at = ? WHERE uuid = ?", statement -> {
            statement.setLong(1, timestamp);
            statement.setString(2, uniqueId.toString());
        });
    }

    @Override
    public void updatePassword(UUID uniqueId, PasswordHash passwordHash) {
        executeUpdate("UPDATE pnauth_users SET algorithm = ?, salt = ?, password_hash = ?, iterations = ? WHERE uuid = ?", statement -> {
            statement.setString(1, passwordHash.algorithm());
            statement.setString(2, passwordHash.salt());
            statement.setString(3, passwordHash.hash());
            statement.setInt(4, passwordHash.iterations());
            statement.setString(5, uniqueId.toString());
        });
    }

    @Override
    public void updateLastIp(UUID uniqueId, String ip) {
        executeUpdate("UPDATE pnauth_users SET last_ip = ? WHERE uuid = ?", statement -> {
            statement.setString(1, ip);
            statement.setString(2, uniqueId.toString());
        });
    }

    @Override
    public void updatePremium(UUID uniqueId, boolean premium) {
        executeUpdate("UPDATE pnauth_users SET premium = ? WHERE uuid = ?", statement -> {
            statement.setBoolean(1, premium);
            statement.setString(2, uniqueId.toString());
        });
    }

    @Override
    public void updateTotpSecret(UUID uniqueId, String encryptedSecret) {
        executeUpdate("UPDATE pnauth_users SET totp_secret = ? WHERE uuid = ?", statement -> {
            statement.setString(1, encryptedSecret);
            statement.setString(2, uniqueId.toString());
        });
    }

    @Override
    public void updateDialogPreference(UUID uniqueId, DialogPreference preference) {
        executeUpdate("UPDATE pnauth_users SET dialog_preference = ? WHERE uuid = ?", statement -> {
            statement.setString(1, preference.name());
            statement.setString(2, uniqueId.toString());
        });
    }

    @Override
    public long countRegisteredIp(String ip) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM pnauth_users WHERE registered_ip = ?")) {
            statement.setString(1, ip);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count auth accounts", exception);
        }
    }

    @Override
    public List<AuthRecord> findAll() {
        List<AuthRecord> records = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT uuid, username, real_name, algorithm, salt, password_hash, iterations, registered_at, "
                             + "last_login_at, premium, registered_ip, last_ip, totp_secret, dialog_preference FROM pnauth_users");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                records.add(read(result));
            }
            return records;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read auth accounts", exception);
        }
    }

    @Override
    public void deleteByUniqueId(UUID uniqueId) {
        executeUpdate("DELETE FROM pnauth_users WHERE uuid = ?", statement -> statement.setString(1, uniqueId.toString()));
        executeUpdate("DELETE FROM pnauth_recovery_codes WHERE uuid = ?", statement -> statement.setString(1, uniqueId.toString()));
    }

    @Override
    public void clearRecoveryCodes(UUID uniqueId) {
        executeUpdate("DELETE FROM pnauth_recovery_codes WHERE uuid = ?", statement -> statement.setString(1, uniqueId.toString()));
    }

    @Override
    public void addRecoveryCode(UUID uniqueId, String codeHash) {
        executeUpdate("INSERT INTO pnauth_recovery_codes (uuid, code_hash) VALUES (?, ?)", statement -> {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, codeHash);
        });
    }

    @Override
    public boolean consumeRecoveryCode(UUID uniqueId, String codeHash) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM pnauth_recovery_codes WHERE uuid = ? AND code_hash = ?")) {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, codeHash);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not consume recovery code", exception);
        }
    }

    private Optional<AuthRecord> find(String sql, String value) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read auth account", exception);
        }
    }

    private AuthRecord read(ResultSet result) throws SQLException {
        String lastLogin = result.getString("last_login_at");
        return new AuthRecord(
                UUID.fromString(result.getString("uuid")),
                result.getString("username"),
                result.getString("real_name"),
                new PasswordHash(
                        result.getString("algorithm"),
                        result.getString("salt"),
                        result.getString("password_hash"),
                        result.getInt("iterations")
                ),
                result.getLong("registered_at"),
                lastLogin == null ? null : Long.parseLong(lastLogin),
                result.getBoolean("premium"),
                result.getString("registered_ip"),
                result.getString("last_ip"),
                result.getString("totp_secret"),
                preference(result.getString("dialog_preference"))
        );
    }

    private void executeUpdate(String sql, StatementBinder binder) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update auth account", exception);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS pnauth_users ("
                    + "uuid VARCHAR(36) PRIMARY KEY, "
                    + "username VARCHAR(16) NOT NULL UNIQUE, "
                    + "real_name VARCHAR(16) NOT NULL, "
                    + "algorithm VARCHAR(16) NOT NULL DEFAULT 'PBKDF2', "
                    + "salt VARCHAR(128) NOT NULL, "
                    + "password_hash VARCHAR(256) NOT NULL, "
                    + "iterations INTEGER NOT NULL, "
                    + "registered_at BIGINT NOT NULL, "
                    + "last_login_at BIGINT NULL, "
                    + "premium BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "registered_ip VARCHAR(128) NULL, "
                    + "last_ip VARCHAR(128) NULL, "
                    + "totp_secret VARCHAR(512) NULL, "
                    + "dialog_preference VARCHAR(16) NOT NULL DEFAULT 'AUTO'"
                    + ")");
            addColumn(statement, "real_name VARCHAR(16) NOT NULL DEFAULT ''");
            addColumn(statement, "algorithm VARCHAR(16) NOT NULL DEFAULT 'PBKDF2'");
            addColumn(statement, "premium BOOLEAN NOT NULL DEFAULT FALSE");
            addColumn(statement, "registered_ip VARCHAR(128) NULL");
            addColumn(statement, "last_ip VARCHAR(128) NULL");
            addColumn(statement, "totp_secret VARCHAR(512) NULL");
            addColumn(statement, "dialog_preference VARCHAR(16) NOT NULL DEFAULT 'AUTO'");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS pnauth_recovery_codes ("
                    + "uuid VARCHAR(36) NOT NULL, "
                    + "code_hash VARCHAR(128) NOT NULL, "
                    + "PRIMARY KEY (uuid, code_hash)"
                    + ")");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize auth database", exception);
        }
    }

    private static void addColumn(Statement statement, String definition) {
        try {
            statement.executeUpdate("ALTER TABLE pnauth_users ADD COLUMN " + definition);
        } catch (SQLException ignored) {
            // The column already exists on current schemas.
        }
    }

    private static DialogPreference preference(String value) {
        if (value == null) return DialogPreference.AUTO;
        try {
            return DialogPreference.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DialogPreference.AUTO;
        }
    }

    private Connection open() throws SQLException {
        if (username.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    private static void loadDriver(String url) {
        String driver = url.startsWith("jdbc:sqlite:")
                ? "org.sqlite.JDBC"
                : url.startsWith("jdbc:mysql:")
                ? "com.mysql.cj.jdbc.Driver"
                : url.startsWith("jdbc:mariadb:")
                ? "org.mariadb.jdbc.Driver"
                : url.startsWith("jdbc:h2:")
                ? "org.h2.Driver"
                : url.startsWith("jdbc:postgresql:")
                ? "org.postgresql.Driver"
                : null;
        if (driver == null) {
            return;
        }
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("JDBC driver is missing: " + driver, exception);
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        String state = exception.getSQLState();
        return "23000".equals(state) || "23505".equals(state) || exception.getErrorCode() == 19;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
