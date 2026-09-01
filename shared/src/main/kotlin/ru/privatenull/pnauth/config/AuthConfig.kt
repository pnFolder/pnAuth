package ru.privatenull.pnauth.config

import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.policy.AccessSettings
import ru.privatenull.pnauth.security.HashAlgorithm
import ru.privatenull.pnauth.extension.AuthOperation
import ru.privatenull.pnauth.cluster.ClusterMode
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneId
import java.util.Locale
import kotlin.jvm.Throws

@JvmRecord
data class AuthConfig(
    val locale: String,
    val storage: StorageConfig,
    val security: AuthSettings,
    val proxy: ProxySettings,
    val access: AccessSettings,
    val features: FeatureSettings,
    val limbo: LimboSettings,
    val paper: PaperSettings,
    val messageFormat: MessageFormat,
    val processingTitle: ProcessingTitleSettings,
    val externalVerification: ExternalVerificationSettings,
    val cluster: ClusterSettings
) {
    @JvmRecord
    data class StorageConfig(val url: String, val username: String, val password: String)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 13
        private const val LEGACY_FORK_DOWNLOAD =
            "https://github.com/pnFolder/PicoLimbo/releases/download/v1.13.2-pn.2%2Bmc26.2/"
        private const val LEGACY_FORK_SHA256 =
            "701ad39c987e01edc659198d166e91d91a3182b8ae7df3bcc7c8366629089e13"

        @JvmStatic
        @Throws(IOException::class)
        fun load(file: Path, fallbackJdbcUrl: String?): AuthConfig {
            return PnAuthConfigManager(file, fallbackJdbcUrl).load()
        }

        @JvmStatic
        @Throws(IOException::class)
        internal fun fromYaml(yaml: PnAuthYamlConfig, configFile: Path, fallbackJdbcUrl: String?): AuthConfig {
            if (yaml.configVersion > CURRENT_SCHEMA_VERSION) {
                throw IOException(
                    "config.yml uses unsupported schema version ${yaml.configVersion}" +
                            "; this pnAuth build supports up to $CURRENT_SCHEMA_VERSION"
                )
            }
            val database = yaml.database ?: PnAuthYamlConfig.Database()
            val servers = yaml.servers ?: PnAuthYamlConfig.Servers()
            val security = yaml.security ?: PnAuthYamlConfig.Security()
            val validation = yaml.validation ?: PnAuthYamlConfig.Validation()
            val access = yaml.access ?: PnAuthYamlConfig.Access()
            val limits = yaml.limits ?: PnAuthYamlConfig.Limits()
            val features = yaml.features ?: PnAuthYamlConfig.Features()
            val ui = yaml.ui ?: PnAuthYamlConfig.Ui()
            val limbo = yaml.limbo ?: PnAuthYamlConfig.Limbo()
            val paper = yaml.paper ?: PnAuthYamlConfig.Paper()
            val external = yaml.externalVerification ?: PnAuthYamlConfig.ExternalVerification()
            val cluster = yaml.cluster ?: PnAuthYamlConfig.Cluster()
            val teleport = paper.teleport ?: PnAuthYamlConfig.Paper.Teleport()
            val successTeleport = paper.successTeleport ?: PnAuthYamlConfig.Paper.SuccessTeleport()
            val restrictions = paper.restrictions ?: PnAuthYamlConfig.Paper.Restrictions()
            val password = security.password ?: PnAuthYamlConfig.Security.Password()
            val login = security.login ?: PnAuthYamlConfig.Security.Login()
            val hashing = security.hashing ?: PnAuthYamlConfig.Security.Hashing()
            val premium = features.premium ?: PnAuthYamlConfig.Features.Premium()
            val session = features.session ?: PnAuthYamlConfig.Features.Session()
            val totp = features.totp ?: PnAuthYamlConfig.Features.Totp()
            val captcha = features.captcha ?: PnAuthYamlConfig.Features.Captcha()
            val dialogs = ui.dialogs ?: PnAuthYamlConfig.Ui.Dialogs()
            val processingTitle = ui.processingTitle ?: PnAuthYamlConfig.Ui.ProcessingTitle()
            migrateLegacyPicoLimboSource(limbo)

            val authTargets = serverTargets(servers.auth, "servers.auth", required = true, allowLimbo = true, limbo = limbo)
            val backendTargets = serverTargets(servers.backend, "servers.backend", required = false, allowLimbo = false, limbo = limbo)

            val authNames = authTargets.map { it.name.lowercase(Locale.ROOT) }.toSet()
            val overlap = backendTargets.firstOrNull { it.name.lowercase(Locale.ROOT) in authNames }
            if (overlap != null) {
                throw IOException(
                    "Server '${overlap.name}' cannot be both AUTH (servers.auth) and BACKEND (servers.backend). " +
                        "Put each server in exactly one routing group."
                )
            }

            val serverLimits = LinkedHashMap<String, Int>()
            val serverTypes = LinkedHashMap<String, ServerTargetType>()
            for (target in authTargets + backendTargets) {
                val existing = serverLimits.putIfAbsent(target.name, target.online)
                if (existing != null && existing != target.online) {
                    throw IOException(
                        "Server '${target.name}' has conflicting online limits ($existing and ${target.online}). " +
                            "Each proxy server must have one online limit."
                    )
                }
                serverTypes[target.name.lowercase(Locale.ROOT)] = target.type
            }
            val defaultServerLimit = serverLimits.values.maxOrNull() ?: 100

            val defaults = AuthSettings.defaults()
            val authSettings = AuthSettings(
                password.minLength,
                password.maxLength,
                login.maxAttempts,
                Duration.ofSeconds(login.lockoutSeconds.toLong()),
                hashing.pbkdf2Iterations,
                validation.usernamePattern,
                enumValue(hashing.algorithm, defaults.hashAlgorithm()),
                hashing.bcryptCost,
                hashing.argon2Iterations,
                hashing.argon2MemoryKb,
                hashing.argon2Parallelism
            )
            return AuthConfig(
                locale(yaml.locale),
                storage(database, configFile, fallbackJdbcUrl),
                authSettings,
                ProxySettings(
                    servers.requireAuthBeforeServer,
                    authServers = authTargets.map { it.name },
                    backendServers = backendTargets.map { it.name },
                    forcedHosts = lowerCaseKeys(servers.forcedHosts),
                    balancerMode = enumValue(servers.balancerMode, ru.privatenull.pnauth.routing.ServerBalancerMode.LEAST_PLAYERS),
                    maxPlayersPerServer = defaultServerLimit,
                    serverLimits = serverLimits,
                    serverTypes = serverTypes
                ),
                AccessSettings(
                    access.blockChat,
                    access.unauthenticatedCommands.toSet(),
                ),
                FeatureSettings(
                    premium.enabled,
                    session.restoreOnSameIp,
                    Duration.ofMinutes(session.lifetimeMinutes.toLong()),
                    Duration.ofSeconds(session.timeoutSeconds.toLong()),
                    Duration.ofSeconds(session.reminderSeconds.toLong()),
                    login.banOnFailedLogin,
                    Duration.ofSeconds(login.banSeconds.toLong()),
                    limits.maxOnlineAccountsPerIp,
                    limits.maxRegisteredAccountsPerIp,
                    limits.excludedIps.toSet(),
                    totp.enabled,
                    totp.maxAttempts,
                    Duration.ofSeconds(totp.lockoutSeconds.toLong()),
                    Duration.ofSeconds(totp.setupLifetimeSeconds.toLong()),
                    totp.issuer,
                    totp.recoveryCodes,
                    password.repeatOnRegister,
                    DialogSettings(
                        dialogs.enabled,
                        dialogs.fallbackToCommands,
                        dialogs.allowPlayerPreference,
                        dialogs.minClientProtocol,
                        dialogs.reopenOnFailure
                    ),
                    CaptchaSettings(
                        captcha.enabled,
                        Duration.ofSeconds(captcha.lifetimeSeconds.toLong()),
                        captcha.maxAttempts
                    ),
                    ui.title,
                    ui.actionbar
                ),
                LimboSettings(
                    limbo.provider,
                    limbo.enabled,
                    limbo.serverName,
                    limbo.host,
                    limbo.port,
                    limbo.autoDownload,
                    limbo.downloadBaseUrl,
                    limbo.downloadSha256
                ),
                PaperSettings(
                    teleport.enabled, teleport.world, teleport.x, teleport.y, teleport.z,
                    teleport.yaw, teleport.pitch,
                    enumValue(successTeleport.destination, PaperSettings.SuccessDestination.ORIGINAL),
                    successTeleport.world, successTeleport.x, successTeleport.y, successTeleport.z,
                    successTeleport.yaw, successTeleport.pitch, successTeleport.delayMillis,
                    restrictions.movement, restrictions.chat,
                    restrictions.commands, restrictions.interaction, restrictions.breaking,
                    restrictions.placing, restrictions.inventory
                ),
                messageFormat(yaml.messages),
                processingTitle(processingTitle),
                externalVerification(external),
                cluster(cluster)
            )
        }

        private fun cluster(source: PnAuthYamlConfig.Cluster): ClusterSettings {
            val mode = try {
                ClusterMode.valueOf(source.mode.trim().uppercase(Locale.ROOT))
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown cluster.mode: ${source.mode}", error)
            }
            return ClusterSettings(
                mode,
                source.nodeId.trim(),
                ClusterSettings.Redis(
                    SecretResolver.resolve(source.redis.uri), source.redis.stream.trim()
                ),
                ClusterSettings.Hub(
                    source.hub.url.trimEnd('/'), source.hub.clientId.trim(),
                    SecretResolver.resolve(source.hub.clientSecret), source.hub.connectTimeoutMillis
                )
            )
        }

        private fun externalVerification(source: PnAuthYamlConfig.ExternalVerification): ExternalVerificationSettings {
            val operations = source.operations.map { value ->
                try {
                    AuthOperation.valueOf(value.trim().uppercase(Locale.ROOT))
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("Unknown external-verification operation: $value", error)
                }
            }.toSet()
            return ExternalVerificationSettings(
                source.enabled,
                operations,
                Duration.ofSeconds(source.lifetimeSeconds.toLong()),
                ExternalVerificationSettings.Callback(
                    source.callback.host.trim(), source.callback.port, source.callback.publicUrl.trimEnd('/')
                ),
                ExternalVerificationSettings.Discord(source.discord.enabled, source.discord.webhookUrl.trim()),
                ExternalVerificationSettings.Telegram(
                    source.telegram.enabled, source.telegram.botToken.trim(), source.telegram.chatId.trim()
                ),
                ExternalVerificationSettings.Vk(
                    source.vk.enabled, source.vk.accessToken.trim(), source.vk.peerId.trim(), source.vk.apiVersion.trim()
                ),
                ExternalVerificationSettings.Custom(
                    source.custom.enabled, source.custom.url.trim(), SecretResolver.resolve(source.custom.secret)
                )
            )
        }

        private data class ParsedServerTarget(
            val name: String,
            val online: Int,
            val type: ServerTargetType
        )

        private fun serverTargets(
            source: List<PnAuthYamlConfig.ServerTarget>?,
            path: String,
            required: Boolean,
            allowLimbo: Boolean,
            limbo: PnAuthYamlConfig.Limbo
        ): List<ParsedServerTarget> {
            val values = source ?: emptyList()
            if (required && values.isEmpty()) {
                throw IOException(
                    "$path must contain at least one server. Example:\n" +
                        "$path:\n  - server: auth\n    online: 100\n    type: SERVER"
                )
            }
            val result = ArrayList<ParsedServerTarget>(values.size)
            val seen = HashSet<String>()
            values.forEachIndexed { index, target ->
                val name = target.server.trim()
                if (name.isEmpty()) {
                    throw IOException("$path[$index].server is empty; specify the route/server name")
                }
                if (target.online < 1) {
                    throw IOException(
                        "$path[$index].online for server '$name' must be greater than 0; got ${target.online}"
                    )
                }
                if (!seen.add(name.lowercase(Locale.ROOT))) {
                    throw IOException("$path contains duplicate server '$name'")
                }

                val configuredType = target.type.trim().uppercase(Locale.ROOT)
                val type = if (configuredType.isEmpty()) {
                    // Compatibility with v1.2.0: only old entries without type may infer embedded Limbo by route name.
                    if (allowLimbo && limbo.enabled && name.equals(limbo.serverName, ignoreCase = true)) {
                        ServerTargetType.LIMBO
                    } else {
                        ServerTargetType.SERVER
                    }
                } else {
                    try {
                        ServerTargetType.valueOf(configuredType)
                    } catch (error: IllegalArgumentException) {
                        throw IOException(
                            "$path[$index].type for '$name' must be SERVER or LIMBO; got '${target.type}'",
                            error
                        )
                    }
                }

                if (type == ServerTargetType.LIMBO) {
                    if (!allowLimbo) {
                        throw IOException("$path[$index] cannot use type LIMBO; backend routes must use type SERVER")
                    }
                    if (!limbo.enabled) {
                        throw IOException(
                            "$path[$index] '$name' uses type LIMBO, but limbo.enabled is false"
                        )
                    }
                    if (!name.equals(limbo.serverName, ignoreCase = true)) {
                        throw IOException(
                            "$path[$index] LIMBO route '$name' must match limbo.server-name '${limbo.serverName}'"
                        )
                    }
                } else if (limbo.enabled && name.equals(limbo.serverName, ignoreCase = true) && configuredType.isNotEmpty()) {
                    throw IOException(
                        "$path[$index] route '$name' is reserved by enabled Limbo. " +
                            "Use type LIMBO, change limbo.server-name, or disable Limbo."
                    )
                }

                result += ParsedServerTarget(name, target.online, type)
            }
            return result
        }

        private fun storage(
            database: PnAuthYamlConfig.Database,
            configFile: Path,
            fallbackJdbcUrl: String?
        ): StorageConfig {
            val type = database.type?.trim()?.uppercase(Locale.ROOT) ?: ""
            if (type == "SQLITE" || type == "H2") {
                if (database.file.isNullOrBlank() && !fallbackJdbcUrl.isNullOrBlank()) {
                    return StorageConfig(fallbackJdbcUrl, "", "")
                }
                if (database.file.isNullOrBlank()) throw IOException("database.file is empty")
                var file = Path.of(database.file)
                if (!file.isAbsolute && configFile.parent != null) file = configFile.parent.resolve(file)
                return StorageConfig(
                    "jdbc:" + type.lowercase(Locale.ROOT) + (if (type == "H2") ":file:" else ":") +
                            file.toAbsolutePath().normalize(), "", ""
                )
            }
            if (type == "MYSQL" || type == "MARIADB" || type == "POSTGRESQL") {
                val connection = if (type == "POSTGRESQL")
                    database.postgresql ?: PnAuthYamlConfig.Connection(5432)
                else
                    database.mysql ?: PnAuthYamlConfig.Connection(3306)
                if (connection.host.isNullOrBlank() || connection.database.isNullOrBlank()
                    || connection.port < 1 || connection.port > 65_535
                ) {
                    throw IOException("Invalid ${type.lowercase(Locale.ROOT)} connection settings")
                }
                val scheme = if (type == "MARIADB") "mariadb" else if (type == "POSTGRESQL") "postgresql" else "mysql"
                val timezone = if (connection.serverTimezone.isNullOrBlank()) "UTC" else connection.serverTimezone
                try {
                    ZoneId.of(timezone)
                } catch (exception: RuntimeException) {
                    throw IOException("Invalid database server timezone: $timezone", exception)
                }
                val query = when (type) {
                    "MYSQL" -> "sslMode=" + (if (connection.useSsl) "VERIFY_IDENTITY" else "DISABLED") +
                            "&serverTimezone=" + timezone
                    "MARIADB" -> "sslMode=" + (if (connection.useSsl) "VERIFY_FULL" else "DISABLE") +
                            "&serverTimezone=" + timezone
                    "POSTGRESQL" -> "sslmode=" + (if (connection.useSsl) "verify-full" else "disable")
                    else -> throw IllegalStateException("Unexpected database type: $type")
                }
                return StorageConfig(
                    "jdbc:$scheme://${connection.host}:${connection.port}/${connection.database}?$query",
                    connection.username ?: "", connection.password ?: ""
                )
            }
            if (type == "JDBC") {
                if (database.url.isNullOrBlank()) throw IOException("database.url is empty")
                return StorageConfig(database.url, database.username ?: "", database.password ?: "")
            }
            throw IOException("Unknown database type: ${database.type}")
        }

        private fun lowerCaseKeys(values: Map<String, String>?): Map<String, String> {
            if (values == null) return emptyMap()
            val result = LinkedHashMap<String, String>()
            values.forEach { (key, value) ->
                result[key.lowercase(Locale.ROOT)] = value
            }
            return result
        }

        @JvmStatic
        fun migrateLegacyPicoLimboSource(limbo: PnAuthYamlConfig.Limbo): Boolean {
            if (LEGACY_FORK_DOWNLOAD == limbo.downloadBaseUrl ||
                LEGACY_FORK_SHA256.equals(limbo.downloadSha256, ignoreCase = true)
            ) {
                limbo.downloadBaseUrl = LimboSettings.OFFICIAL_DOWNLOAD_BASE_URL
                limbo.downloadSha256 = LimboSettings.OFFICIAL_DOWNLOAD_SHA256
                return true
            }
            return false
        }

        private fun <T : Enum<T>> enumValue(value: String?, fallback: T): T {
            if (value == null) return fallback
            return try {
                java.lang.Enum.valueOf(fallback.declaringJavaClass, value.uppercase(Locale.ROOT))
            } catch (exception: RuntimeException) {
                throw IOException("Invalid enum value: $value", exception)
            }
        }

        private fun messageFormat(messages: PnAuthYamlConfig.Messages?): MessageFormat {
            return messages?.format ?: MessageFormat.LEGACY
        }

        private fun processingTitle(value: PnAuthYamlConfig.Ui.ProcessingTitle): ProcessingTitleSettings {
            val animation = value.animation ?: PnAuthYamlConfig.Ui.ProcessingTitle.Animation()
            val timings = value.timings ?: PnAuthYamlConfig.Ui.ProcessingTitle.Timings()
            return try {
                ProcessingTitleSettings(
                    value.enabled,
                    ProcessingTitleSettings.Animation(
                        ProcessingTitleSettings.Type.valueOf(animation.type.uppercase(Locale.ROOT)),
                        animation.colors ?: emptyList(),
                        animation.frameCount,
                        animation.frames ?: emptyList()
                    ),
                    ProcessingTitleSettings.Timings(
                        Duration.ofMillis(Math.multiplyExact(timings.frameIntervalTicks.toLong(), 50L)),
                        Duration.ofMillis(timings.minimumDisplayMillis.toLong()),
                        Duration.ofMillis(timings.resultFadeInMillis.toLong()),
                        Duration.ofMillis(timings.resultDisplayMillis.toLong()),
                        Duration.ofMillis(timings.resultFadeOutMillis.toLong()),
                        Duration.ofMillis(timings.fadeInMillis.toLong()),
                        Duration.ofMillis(timings.stayMillis.toLong()),
                        Duration.ofMillis(timings.fadeOutMillis.toLong())
                    )
                )
            } catch (exception: RuntimeException) {
                throw IOException("Invalid ui.processing-title settings", exception)
            }
        }

        private fun locale(value: String?): String {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: "ru"
            if (normalized == "ru" || normalized == "en") return normalized
            throw IOException("Unsupported locale: $value. Supported locales: ru, en")
        }

        private object SetDefaults {
            fun commands(): Set<String> {
                return AccessSettings.defaults().unauthenticatedCommands()
            }
        }
    }
}
