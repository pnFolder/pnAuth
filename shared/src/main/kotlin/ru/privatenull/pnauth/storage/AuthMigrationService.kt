package ru.privatenull.pnauth.storage

import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Imports common auth schemas without depending on the source plugin. */
class AuthMigrationService(private val target: AuthRepository) : AutoCloseable {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pnauth-migration").apply {
            isDaemon = true
        }
    }

    fun migrate(
        source: Source,
        jdbcUrl: String,
        username: String?,
        password: String?
    ): CompletableFuture<Int> {
        return CompletableFuture.supplyAsync({ run(source, jdbcUrl, username, password) }, executor)
    }

    private fun run(source: Source, url: String, username: String?, password: String?): Int {
        var migrated = 0
        val connection = if (username.isNullOrBlank()) {
            DriverManager.getConnection(url)
        } else {
            DriverManager.getConnection(url, username, password ?: "")
        }
        try {
            connection.use { conn ->
                conn.createStatement().use { statement ->
                    statement.executeQuery(source.query).use { result ->
                        while (result.next()) {
                            val record = source.read(result)
                            if (target.create(record)) migrated++
                        }
                        return migrated
                    }
                }
            }
        } catch (exception: SQLException) {
            throw IllegalStateException("Could not migrate authentication database", exception)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    enum class Source(val query: String) {
        TIAUTH("SELECT username, realName, password, premium, lastIp, regIp, lastLogin, regDate FROM auth_users") {
            override fun read(result: ResultSet): AuthRecord {
                return record(
                    result.getString("username"), result.getString("realName"), result.getString("password"),
                    result.getBoolean("premium"), result.getString("regIp"), result.getString("lastIp"),
                    result.getLong("regDate"), result.getLong("lastLogin")
                )
            }
        },
        AUTHME("SELECT username, realname, password, regip, lastlogin, regdate FROM authme") {
            override fun read(result: ResultSet): AuthRecord {
                return record(
                    result.getString("username"), result.getString("realname"), result.getString("password"),
                    false, result.getString("regip"), result.getString("regip"), timestamp(result, "regdate"),
                    timestamp(result, "lastlogin")
                )
            }
        },
        MCAUTH("SELECT player_id, player_name, password_hash, last_ip, last_session_start FROM mc_auth_accounts") {
            override fun read(result: ResultSet): AuthRecord {
                return record(
                    result.getString("player_id"), result.getString("player_name"),
                    result.getString("password_hash"), false, result.getString("last_ip"), result.getString("last_ip"),
                    result.getLong("last_session_start"), result.getLong("last_session_start")
                )
            }
        },
        LIMBOAUTH("SELECT LOWERCASENICKNAME, NICKNAME, HASH, PREMIUMUUID, LOGINIP, IP, LOGINDATE, REGDATE FROM AUTH") {
            override fun read(result: ResultSet): AuthRecord {
                val premiumUuid = result.getString("PREMIUMUUID")
                return record(
                    result.getString("LOWERCASENICKNAME"), result.getString("NICKNAME"), result.getString("HASH"),
                    !premiumUuid.isNullOrBlank(), result.getString("IP"), result.getString("LOGINIP"),
                    result.getLong("REGDATE"), result.getLong("LOGINDATE")
                )
            }
        },
        NLOGIN("SELECT last_name, password, last_ip, last_seen, creation_date FROM nlogin") {
            override fun read(result: ResultSet): AuthRecord {
                return record(
                    result.getString("last_name"), result.getString("last_name"), result.getString("password"),
                    false, result.getString("last_ip"), result.getString("last_ip"),
                    timestamp(result, "creation_date"), timestamp(result, "last_seen")
                )
            }
        };

        abstract fun read(result: ResultSet): AuthRecord

        companion object {
            @JvmStatic
            protected fun record(
                username: String,
                realName: String,
                password: String?,
                premium: Boolean,
                registeredIp: String?,
                lastIp: String?,
                registeredAt: Long,
                lastLoginAt: Long
            ): AuthRecord {
                val normalized = username.lowercase(Locale.ROOT)
                val algorithm = if (password != null && password.startsWith("${'$'}2")) "BCRYPT"
                else if (password != null && password.startsWith("${'$'}argon2")) "ARGON2"
                else if (password != null && password.startsWith("${'$'}SHA${'$'}")) "SHA256_AUTHME"
                else "SHA256"
                return AuthRecord(
                    UUID.nameUUIDFromBytes("OfflinePlayer:$realName".toByteArray(StandardCharsets.UTF_8)),
                    normalized,
                    realName,
                    PasswordHash.legacy(algorithm, password ?: ""),
                    registeredAt,
                    lastLoginAt,
                    premium,
                    registeredIp,
                    lastIp,
                    null
                )
            }

            @JvmStatic
            protected fun timestamp(result: ResultSet, column: String): Long {
                val timestamp = result.getTimestamp(column)
                return timestamp?.time ?: System.currentTimeMillis()
            }
        }
    }
}
