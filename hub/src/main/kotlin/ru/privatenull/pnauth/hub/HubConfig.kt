package ru.privatenull.pnauth.hub

import net.elytrium.serializer.SerializerConfig
import net.elytrium.serializer.annotations.Comment
import net.elytrium.serializer.annotations.CommentValue
import net.elytrium.serializer.annotations.Transient
import net.elytrium.serializer.language.`object`.YamlSerializable
import ru.privatenull.pnauth.config.SecretResolver
import java.nio.file.Files
import java.nio.file.Path

class HubConfig(path: Path) : YamlSerializable(path, SERIALIZER) {
    @Comment(CommentValue("Версия схемы pnAuth Hub. Обновляется автоматически."))
    @JvmField var configVersion: Int = 1

    @JvmField var server: Server = Server()
    @JvmField var database: Database = Database()

    @Comment(
        CommentValue("Клиенты Hub: ключ — client-id узла, значение — секрет минимум из 32 символов."),
        CommentValue("Рекомендуемый формат значения: ${'$'}{ENV:PNAUTH_CLIENT_PROXY_1}.")
    )
    @JvmField var clients: Map<String, String> = linkedMapOf("proxy-1" to "${'$'}{ENV:PNAUTH_CLIENT_PROXY_1}")

    class Server {
        @Comment(CommentValue("Локальный адрес Hub. Для production оставьте loopback и поставьте HTTPS reverse proxy."))
        @JvmField var host: String = "127.0.0.1"
        @Comment(CommentValue("Локальный порт HTTP API Hub."))
        @JvmField var port: Int = 8780
        @Comment(CommentValue("Максимальный размер JSON-запроса в байтах."))
        @JvmField var maxRequestBytes: Int = 16_384
        @Comment(CommentValue("Допустимое расхождение часов подписанного запроса, в секундах."))
        @JvmField var timestampToleranceSeconds: Int = 30
        @Comment(CommentValue("Максимум credential-запросов одного клиента за минуту."))
        @JvmField var requestsPerMinute: Int = 300
    }

    class Database {
        @Comment(CommentValue("JDBC URL центральной базы Hub. Для production используйте PostgreSQL/MariaDB/MySQL."))
        @JvmField var url: String = "jdbc:sqlite:pnauth-hub.db"
        @Comment(CommentValue("Пользователь SQL-базы или ссылка ${'$'}{ENV:PNAUTH_HUB_DB_USER}."))
        @JvmField var username: String = ""
        @Comment(CommentValue("Пароль SQL-базы или ссылка ${'$'}{ENV:PNAUTH_HUB_DB_PASSWORD}."))
        @JvmField var password: String = ""
    }

    fun validated(): Runtime {
        require(server.host.isNotBlank() && server.port in 1..65535) { "Некорректный server.host/server.port" }
        require(server.maxRequestBytes in 1024..1_048_576) { "Некорректный server.max-request-bytes" }
        require(server.timestampToleranceSeconds in 5..300) { "Некорректный timestamp tolerance" }
        require(server.requestsPerMinute > 0) { "Некорректный requests-per-minute" }
        val resolvedClients = clients.mapValues { (_, secret) -> SecretResolver.resolve(secret) }
        require(resolvedClients.isNotEmpty()) { "Нужен хотя бы один Hub client" }
        require(resolvedClients.all { (id, secret) -> id.matches(Regex("[A-Za-z0-9._-]{1,64}")) && secret.length >= 32 }) {
            "Client ID некорректен или client secret короче 32 символов"
        }
        return Runtime(
            server.host, server.port, server.maxRequestBytes, server.timestampToleranceSeconds,
            server.requestsPerMinute, database.url, SecretResolver.resolve(database.username),
            SecretResolver.resolve(database.password), resolvedClients
        )
    }

    data class Runtime(
        val host: String, val port: Int, val maxRequestBytes: Int, val timestampToleranceSeconds: Int,
        val requestsPerMinute: Int, val databaseUrl: String, val databaseUsername: String,
        val databasePassword: String, val clients: Map<String, String>
    )

    companion object {
        fun load(path: Path): Runtime {
            Files.createDirectories(path.toAbsolutePath().parent)
            val config = HubConfig(path)
            if (Files.notExists(path)) config.save() else config.reload()
            return config.validated()
        }

        @Transient private val SERIALIZER = SerializerConfig.Builder().setCommentValueIndent(1).build()
    }
}
