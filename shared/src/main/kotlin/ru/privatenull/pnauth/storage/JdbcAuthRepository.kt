package ru.privatenull.pnauth.storage

import ru.privatenull.pnauth.api.DialogPreference
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.ArrayList
import java.util.Locale
import java.util.Optional
import java.util.UUID

class JdbcAuthRepository @JvmOverloads constructor(
    private val url: String,
    username: String? = null,
    password: String? = null
) : AuthRepository {

    private val username: String = username ?: ""
    private val password: String = password ?: ""

    init {
        loadDriver(url)
        initialize()
    }

    override fun findByUniqueId(uniqueId: UUID): Optional<AuthRecord> {
        return find(
            "SELECT uuid, username, real_name, algorithm, salt, password_hash, iterations, registered_at, " +
                    "last_login_at, premium, registered_ip, last_ip, totp_secret, dialog_preference " +
                    "FROM pnauth_users WHERE uuid = ?", uniqueId.toString()
        )
    }

    override fun findByUsername(username: String): Optional<AuthRecord> {
        return find(
            "SELECT uuid, username, real_name, algorithm, salt, password_hash, iterations, registered_at, " +
                    "last_login_at, premium, registered_ip, last_ip, totp_secret, dialog_preference " +
                    "FROM pnauth_users WHERE username = ?", username.lowercase(Locale.ROOT)
        )
    }

    override fun create(record: AuthRecord): Boolean {
        val sql = "INSERT INTO pnauth_users " +
                "(uuid, username, real_name, algorithm, salt, password_hash, iterations, registered_at, " +
                "last_login_at, premium, registered_ip, last_ip, totp_secret, dialog_preference) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        try {
            open().use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, record.uniqueId.toString())
                    statement.setString(2, record.username)
                    statement.setString(3, record.realName)
                    statement.setString(4, record.passwordHash.algorithm)
                    statement.setString(5, record.passwordHash.salt)
                    statement.setString(6, record.passwordHash.hash)
                    statement.setInt(7, record.passwordHash.iterations)
                    statement.setLong(8, record.registeredAt)
                    if (record.lastLoginAt == null) {
                        statement.setNull(9, Types.BIGINT)
                    } else {
                        statement.setLong(9, record.lastLoginAt!!)
                    }
                    statement.setBoolean(10, record.premium)
                    statement.setString(11, record.registeredIp)
                    statement.setString(12, record.lastIp)
                    statement.setString(13, record.totpSecret)
                    statement.setString(14, record.dialogPreference.name)
                    statement.executeUpdate()
                    return true
                }
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                return false
            }
            throw IllegalStateException("Could not create auth account", exception)
        }
    }

    override fun updateUsername(uniqueId: UUID, username: String): Boolean {
        try {
            open().use { connection ->
                connection.prepareStatement("UPDATE pnauth_users SET username = ? WHERE uuid = ?").use { statement ->
                    statement.setString(1, username.lowercase(Locale.ROOT))
                    statement.setString(2, uniqueId.toString())
                    statement.executeUpdate()
                    return true
                }
            }
        } catch (exception: SQLException) {
            if (isConstraintViolation(exception)) {
                return false
            }
            throw IllegalStateException("Could not update auth username", exception)
        }
    }

    override fun reassignUniqueId(previousUniqueId: UUID, currentUniqueId: UUID): Boolean {
        if (previousUniqueId == currentUniqueId) return true
        try {
            open().use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("UPDATE pnauth_users SET uuid = ? WHERE uuid = ?").use { account ->
                        connection.prepareStatement("UPDATE pnauth_recovery_codes SET uuid = ? WHERE uuid = ?").use { recovery ->
                            account.setString(1, currentUniqueId.toString())
                            account.setString(2, previousUniqueId.toString())
                            if (account.executeUpdate() != 1) {
                                connection.rollback()
                                return false
                            }
                            recovery.setString(1, currentUniqueId.toString())
                            recovery.setString(2, previousUniqueId.toString())
                            recovery.executeUpdate()
                            connection.commit()
                            return true
                        }
                    }
                } catch (exception: SQLException) {
                    connection.rollback()
                    if (isConstraintViolation(exception)) return false
                    throw exception
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not reassign auth account UUID", exception)
        }
    }

    override fun updateLastLogin(uniqueId: UUID, timestamp: Long) {
        executeUpdate("UPDATE pnauth_users SET last_login_at = ? WHERE uuid = ?") { statement ->
            statement.setLong(1, timestamp)
            statement.setString(2, uniqueId.toString())
        }
    }

    override fun updatePassword(uniqueId: UUID, passwordHash: PasswordHash) {
        executeUpdate("UPDATE pnauth_users SET algorithm = ?, salt = ?, password_hash = ?, iterations = ? WHERE uuid = ?") { statement ->
            statement.setString(1, passwordHash.algorithm())
            statement.setString(2, passwordHash.salt())
            statement.setString(3, passwordHash.hash())
            statement.setInt(4, passwordHash.iterations())
            statement.setString(5, uniqueId.toString())
        }
    }

    override fun updateLastIp(uniqueId: UUID, ip: String?) {
        executeUpdate("UPDATE pnauth_users SET last_ip = ? WHERE uuid = ?") { statement ->
            statement.setString(1, ip)
            statement.setString(2, uniqueId.toString())
        }
    }

    override fun updatePremium(uniqueId: UUID, premium: Boolean) {
        executeUpdate("UPDATE pnauth_users SET premium = ? WHERE uuid = ?") { statement ->
            statement.setBoolean(1, premium)
            statement.setString(2, uniqueId.toString())
        }
    }

    override fun updateTotpSecret(uniqueId: UUID, encryptedSecret: String?) {
        executeUpdate("UPDATE pnauth_users SET totp_secret = ? WHERE uuid = ?") { statement ->
            statement.setString(1, encryptedSecret)
            statement.setString(2, uniqueId.toString())
        }
    }

    override fun updateDialogPreference(uniqueId: UUID, preference: DialogPreference) {
        executeUpdate("UPDATE pnauth_users SET dialog_preference = ? WHERE uuid = ?") { statement ->
            statement.setString(1, preference.name)
            statement.setString(2, uniqueId.toString())
        }
    }

    override fun countRegisteredIp(ip: String): Long {
        try {
            open().use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM pnauth_users WHERE registered_ip = ?").use { statement ->
                    statement.setString(1, ip)
                    statement.executeQuery().use { result ->
                        return if (result.next()) result.getLong(1) else 0
                    }
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not count auth accounts", exception)
        }
    }

    override fun findAll(): List<AuthRecord> {
        val records = ArrayList<AuthRecord>()
        try {
            open().use { connection ->
                connection.prepareStatement(
                    "SELECT uuid, username, real_name, algorithm, salt, password_hash, iterations, registered_at, " +
                            "last_login_at, premium, registered_ip, last_ip, totp_secret, dialog_preference FROM pnauth_users"
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        while (result.next()) {
                            records.add(read(result))
                        }
                        return records
                    }
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not read auth accounts", exception)
        }
    }

    override fun deleteByUniqueId(uniqueId: UUID) {
        try {
            open().use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("DELETE FROM pnauth_users WHERE uuid = ?").use { account ->
                        connection.prepareStatement("DELETE FROM pnauth_recovery_codes WHERE uuid = ?").use { recovery ->
                            account.setString(1, uniqueId.toString())
                            account.executeUpdate()
                            recovery.setString(1, uniqueId.toString())
                            recovery.executeUpdate()
                            connection.commit()
                        }
                    }
                } catch (exception: SQLException) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not delete auth account", exception)
        }
    }

    override fun clearRecoveryCodes(uniqueId: UUID) {
        executeUpdate("DELETE FROM pnauth_recovery_codes WHERE uuid = ?") { statement ->
            statement.setString(1, uniqueId.toString())
        }
    }

    override fun addRecoveryCode(uniqueId: UUID, codeHash: String) {
        executeUpdate("INSERT INTO pnauth_recovery_codes (uuid, code_hash) VALUES (?, ?)") { statement ->
            statement.setString(1, uniqueId.toString())
            statement.setString(2, codeHash)
        }
    }

    override fun consumeRecoveryCode(uniqueId: UUID, codeHash: String): Boolean {
        try {
            open().use { connection ->
                connection.prepareStatement("DELETE FROM pnauth_recovery_codes WHERE uuid = ? AND code_hash = ?").use { statement ->
                    statement.setString(1, uniqueId.toString())
                    statement.setString(2, codeHash)
                    return statement.executeUpdate() > 0
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not consume recovery code", exception)
        }
    }

    override fun replaceTotpData(uniqueId: UUID, encryptedSecret: String, recoveryCodeHashes: List<String>) {
        try {
            open().use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("UPDATE pnauth_users SET totp_secret = ? WHERE uuid = ?").use { secret ->
                        connection.prepareStatement("DELETE FROM pnauth_recovery_codes WHERE uuid = ?").use { clear ->
                            connection.prepareStatement("INSERT INTO pnauth_recovery_codes (uuid, code_hash) VALUES (?, ?)").use { add ->
                                secret.setString(1, encryptedSecret)
                                secret.setString(2, uniqueId.toString())
                                secret.executeUpdate()
                                clear.setString(1, uniqueId.toString())
                                clear.executeUpdate()
                                for (codeHash in recoveryCodeHashes) {
                                    add.setString(1, uniqueId.toString())
                                    add.setString(2, codeHash)
                                    add.addBatch()
                                }
                                add.executeBatch()
                                connection.commit()
                            }
                        }
                    }
                } catch (exception: SQLException) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not replace TOTP data", exception)
        }
    }

    override fun clearTotpData(uniqueId: UUID) {
        try {
            open().use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("UPDATE pnauth_users SET totp_secret = NULL WHERE uuid = ?").use { secret ->
                        connection.prepareStatement("DELETE FROM pnauth_recovery_codes WHERE uuid = ?").use { clear ->
                            secret.setString(1, uniqueId.toString())
                            secret.executeUpdate()
                            clear.setString(1, uniqueId.toString())
                            clear.executeUpdate()
                            connection.commit()
                        }
                    }
                } catch (exception: SQLException) {
                    connection.rollback()
                    throw exception
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not clear TOTP data", exception)
        }
    }

    private fun find(sql: String, value: String): Optional<AuthRecord> {
        try {
            open().use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, value)
                    statement.executeQuery().use { result ->
                        return if (result.next()) Optional.of(read(result)) else Optional.empty()
                    }
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not read auth account", exception)
        }
    }

    private fun read(result: ResultSet): AuthRecord {
        val lastLogin = result.getString("last_login_at")
        return AuthRecord(
            UUID.fromString(result.getString("uuid")),
            result.getString("username"),
            result.getString("real_name"),
            PasswordHash(
                result.getString("algorithm"),
                result.getString("salt"),
                result.getString("password_hash"),
                result.getInt("iterations")
            ),
            result.getLong("registered_at"),
            lastLogin?.toLongOrNull(),
            result.getBoolean("premium"),
            result.getString("registered_ip"),
            result.getString("last_ip"),
            result.getString("totp_secret"),
            preference(result.getString("dialog_preference"))
        )
    }

    private fun executeUpdate(sql: String, binder: StatementBinder) {
        try {
            open().use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    binder.bind(statement)
                    statement.executeUpdate()
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not update auth account", exception)
        }
    }

    private fun initialize() {
        try {
            open().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS pnauth_users (" +
                                "uuid VARCHAR(36) PRIMARY KEY, " +
                                "username VARCHAR(16) NOT NULL UNIQUE, " +
                                "real_name VARCHAR(16) NOT NULL, " +
                                "algorithm VARCHAR(16) NOT NULL DEFAULT 'PBKDF2', " +
                                "salt VARCHAR(128) NOT NULL, " +
                                "password_hash VARCHAR(256) NOT NULL, " +
                                "iterations INTEGER NOT NULL, " +
                                "registered_at BIGINT NOT NULL, " +
                                "last_login_at BIGINT NULL, " +
                                "premium BOOLEAN NOT NULL DEFAULT FALSE, " +
                                "registered_ip VARCHAR(128) NULL, " +
                                "last_ip VARCHAR(128) NULL, " +
                                "totp_secret VARCHAR(512) NULL, " +
                                "dialog_preference VARCHAR(16) NOT NULL DEFAULT 'AUTO'" +
                                ")"
                    )
                    addColumn(statement, "real_name VARCHAR(16) NOT NULL DEFAULT ''")
                    addColumn(statement, "algorithm VARCHAR(16) NOT NULL DEFAULT 'PBKDF2'")
                    addColumn(statement, "premium BOOLEAN NOT NULL DEFAULT FALSE")
                    addColumn(statement, "registered_ip VARCHAR(128) NULL")
                    addColumn(statement, "last_ip VARCHAR(128) NULL")
                    addColumn(statement, "totp_secret VARCHAR(512) NULL")
                    addColumn(statement, "dialog_preference VARCHAR(16) NOT NULL DEFAULT 'AUTO'")
                    statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS pnauth_recovery_codes (" +
                                "uuid VARCHAR(36) NOT NULL, " +
                                "code_hash VARCHAR(128) NOT NULL, " +
                                "PRIMARY KEY (uuid, code_hash)" +
                                ")"
                    )
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not initialize auth database", exception)
        }
    }

    private fun open(): Connection {
        return if (username.isBlank()) {
            DriverManager.getConnection(url)
        } else {
            DriverManager.getConnection(url, username, password)
        }
    }

    private fun interface StatementBinder {
        @Throws(SQLException::class)
        fun bind(statement: PreparedStatement)
    }

    companion object {
        private fun addColumn(statement: Statement, definition: String) {
            try {
                statement.executeUpdate("ALTER TABLE pnauth_users ADD COLUMN $definition")
            } catch (exception: SQLException) {
                if (!isDuplicateColumn(exception)) {
                    throw IllegalStateException("Could not migrate auth database column: $definition", exception)
                }
            }
        }

        private fun isDuplicateColumn(exception: SQLException): Boolean {
            val state = exception.sqlState
            if ("42701" == state || "42S21" == state || exception.errorCode == 1060 || exception.errorCode == 42121) {
                return true
            }
            val message = exception.message
            return message != null && message.lowercase(Locale.ROOT).contains("duplicate column")
        }

        private fun preference(value: String?): DialogPreference {
            if (value == null) return DialogPreference.AUTO
            return try {
                DialogPreference.valueOf(value.uppercase(Locale.ROOT))
            } catch (ignored: IllegalArgumentException) {
                DialogPreference.AUTO
            }
        }

        private fun loadDriver(url: String) {
            val driver = if (url.startsWith("jdbc:sqlite:")) "org.sqlite.JDBC"
            else if (url.startsWith("jdbc:mysql:")) "com.mysql.cj.jdbc.Driver"
            else if (url.startsWith("jdbc:mariadb:")) "org.mariadb.jdbc.Driver"
            else if (url.startsWith("jdbc:h2:")) "org.h2.Driver"
            else if (url.startsWith("jdbc:postgresql:")) "org.postgresql.Driver"
            else null
            if (driver == null) return
            try {
                Class.forName(driver)
            } catch (exception: ClassNotFoundException) {
                throw IllegalStateException("JDBC driver is missing: $driver", exception)
            }
        }

        private fun isConstraintViolation(exception: SQLException): Boolean {
            val state = exception.sqlState
            return "23000" == state || "23505" == state || exception.errorCode == 19
        }
    }
}
