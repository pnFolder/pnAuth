package ru.privatenull.pnauth.platform

import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.command.AuthPlatformBridge
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.display.PlayerDisplay
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator
import ru.privatenull.pnauth.limbo.LimboServer
import ru.privatenull.pnauth.limbo.LimboServerContext
import ru.privatenull.pnauth.limbo.LimboServerProvider
import ru.privatenull.pnauth.limbo.LimboServerRegistry
import ru.privatenull.pnauth.limbo.PicoLimboProvider
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.policy.AuthAccessService
import ru.privatenull.pnauth.security.TotpKeyStore
import ru.privatenull.pnauth.security.TotpService
import ru.privatenull.pnauth.service.AuthService
import ru.privatenull.pnauth.storage.AuthMigrationService
import ru.privatenull.pnauth.storage.JdbcAuthRepository
import java.nio.file.Path

/**
 * Fluent bootstrap builder and service registry container for initializing pnAuth
 * consistently across BungeeCord, Velocity, Paper, and Folia.
 */
class PnAuthBootstrap private constructor(
    val dataFolder: Path,
    val logger: PlatformLogger,
    val config: AuthConfig,
    val proxySettings: ProxySettings,
    val limbo: LimboServer?,
    val repository: JdbcAuthRepository,
    val authService: AuthService,
    val messages: AuthMessages,
    val authBridge: AuthPlatformBridge,
    val migration: AuthMigrationService,
    val commandService: AuthCommandService,
    val accessService: AuthAccessService,
    val lifecycleCoordinator: AuthLifecycleCoordinator,
    val platform: PnPlatform?
) : AutoCloseable {

    override fun close() {
        authService.close()
        migration.close()
        limbo?.close()
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var folder: Path? = null
        private var platformLogger: PlatformLogger? = null
        private var platformAdapter: PnPlatform? = null
        private var displayAdapter: PlayerDisplay? = null
        private var bridgeAdapter: AuthPlatformBridge? = null
        private val limboProviders: MutableList<LimboServerProvider> = mutableListOf(PicoLimboProvider())

        fun dataFolder(path: Path): Builder {
            this.folder = path
            return this
        }

        fun logger(logger: PlatformLogger): Builder {
            this.platformLogger = logger
            return this
        }

        fun logger(consumer: (String) -> Unit): Builder {
            this.platformLogger = PlatformLogger.of(consumer)
            return this
        }

        fun platform(platform: PnPlatform): Builder {
            this.platformAdapter = platform
            return this
        }

        fun display(display: PlayerDisplay): Builder {
            this.displayAdapter = display
            return this
        }

        fun authBridge(bridge: AuthPlatformBridge): Builder {
            this.bridgeAdapter = bridge
            return this
        }

        fun registerLimboProvider(provider: LimboServerProvider): Builder {
            this.limboProviders.add(provider)
            return this
        }

        fun build(): PnAuthBootstrap {
            val dataFolder = folder ?: throw IllegalStateException("dataFolder must be specified")
            val logger = platformLogger ?: PlatformLogger.of { println(it) }

            val defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize()
            val config = AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl)
            var proxySettings = config.proxy

            val limboRegistry = LimboServerRegistry()
            for (provider in limboProviders) {
                limboRegistry.register(provider)
            }

            var limboServer: LimboServer? = null
            if (config.limbo.enabled) {
                if (!config.proxy.authServer.equals(config.limbo.serverName, ignoreCase = true)) {
                    throw IllegalArgumentException("proxy.auth-server must equal limbo.server-name when limbo is enabled")
                }
                if (config.proxy.backendServer.equals(config.limbo.serverName, ignoreCase = true)) {
                    throw IllegalArgumentException("servers.backend-server must differ from limbo.server-name")
                }
                try {
                    val created = limboRegistry.create(config.limbo.provider, LimboServerContext(dataFolder, config.limbo))
                    created.start()
                    limboServer = created
                    proxySettings = proxySettings.requiringServerAuth()
                } catch (exception: Exception) {
                    limboServer?.close()
                    throw IllegalStateException("Embedded PicoLimbo is enabled but could not be started", exception)
                }
            }

            val repository = JdbcAuthRepository(
                config.storage.url,
                config.storage.username,
                config.storage.password
            )

            val authService = AuthService(
                repository, config.security, TotpService(
                    repository, TotpKeyStore.loadOrCreate(dataFolder.resolve("totp.key"))
                ), config.features
            )

            displayAdapter?.let { authService.installDisplay(it) }
            platformAdapter?.let { authService.installPlatform(it) }

            val messages = AuthMessages.load(dataFolder.resolve("messages"), config.locale, config.messageFormat)
            val bridge = bridgeAdapter ?: AuthPlatformBridge.NONE
            val migration = AuthMigrationService(repository)
            val commandService = AuthCommandService(authService, messages, bridge, migration, config.features)
            val accessService = AuthAccessService(authService, proxySettings, config.access, messages)
            val lifecycleCoordinator = AuthLifecycleCoordinator(authService, accessService)

            logger.info("pnAuth core services initialized successfully.")

            return PnAuthBootstrap(
                dataFolder, logger, config, proxySettings, limboServer,
                repository, authService, messages, bridge, migration,
                commandService, accessService, lifecycleCoordinator, platformAdapter
            )
        }
    }
}
