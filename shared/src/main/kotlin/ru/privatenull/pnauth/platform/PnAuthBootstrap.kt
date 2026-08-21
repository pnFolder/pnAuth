package ru.privatenull.pnauth.platform

import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator
import ru.privatenull.pnauth.limbo.LimboServer
import ru.privatenull.pnauth.limbo.LimboServerContext
import ru.privatenull.pnauth.limbo.LimboServerProvider
import ru.privatenull.pnauth.limbo.LimboServerRegistry
import ru.privatenull.pnauth.limbo.PicoLimboProvider
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.platform.adapter.PlatformAuthBridgeAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformDialogAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformDisplayAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformLimboAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformLoggerAdapter
import ru.privatenull.pnauth.policy.AuthAccessService
import ru.privatenull.pnauth.security.TotpKeyStore
import ru.privatenull.pnauth.security.TotpService
import ru.privatenull.pnauth.service.AuthService
import ru.privatenull.pnauth.storage.AuthMigrationService
import ru.privatenull.pnauth.storage.JdbcAuthRepository
import ru.privatenull.pnauth.verification.ExternalVerificationService
import ru.privatenull.pnauth.cluster.AuthClusterCoordinator
import ru.privatenull.pnauth.cluster.ClusterMode
import ru.privatenull.pnauth.cluster.DatabaseClusterTransport
import ru.privatenull.pnauth.cluster.NoopClusterTransport
import ru.privatenull.pnauth.cluster.RedisClusterTransport
import ru.privatenull.pnauth.cluster.HubClusterTransport
import ru.privatenull.pnauth.hub.HubApiClient
import ru.privatenull.pnauth.hub.HubCredentialAuthority
import java.nio.file.Path

/**
 * Fluent bootstrap builder and service registry container for initializing pnAuth
 * consistently using clean Platform adapters across BungeeCord, Velocity, Paper, and Folia.
 */
class PnAuthBootstrap private constructor(
    val dataFolder: Path,
    val logger: PlatformLoggerAdapter,
    val config: AuthConfig,
    val proxySettings: ProxySettings,
    val limbo: LimboServer?,
    val repository: JdbcAuthRepository,
    val authService: AuthService,
    val messages: AuthMessages,
    val authBridge: PlatformAuthBridgeAdapter,
    val migration: AuthMigrationService,
    val commandService: AuthCommandService,
    val accessService: AuthAccessService,
    val lifecycleCoordinator: AuthLifecycleCoordinator,
    val platform: Platform?,
    val proxy: Proxy?
) : AutoCloseable {

    private var externalVerification: ExternalVerificationService? = null
    private var clusterCoordinator: AuthClusterCoordinator? = null

    override fun close() {
        clusterCoordinator?.close()
        externalVerification?.close()
        limbo?.let { server ->
            proxy?.unregisterServerRoute(config.limbo.serverName)
            server.close()
        }
        authService.close()
        migration.close()
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var folder: Path? = null
        private var platformLoggerAdapter: PlatformLoggerAdapter? = null
        private var platformAdapter: Platform? = null
        private var displayAdapter: PlatformDisplayAdapter? = null
        private var dialogAdapter: PlatformDialogAdapter? = null
        private var bridgeAdapter: PlatformAuthBridgeAdapter? = null
        private var limboAdapter: PlatformLimboAdapter? = null
        private var proxyAdapter: Proxy? = null
        private val limboProviders: MutableList<LimboServerProvider> = mutableListOf(PicoLimboProvider())

        fun dataFolder(path: Path): Builder {
            this.folder = path
            return this
        }

        fun logger(logger: PlatformLoggerAdapter): Builder {
            this.platformLoggerAdapter = logger
            return this
        }

        fun logger(consumer: (String) -> Unit): Builder {
            this.platformLoggerAdapter = PlatformLoggerAdapter.of(consumer)
            return this
        }

        fun platform(platform: Platform): Builder {
            this.platformAdapter = platform
            return this
        }

        fun display(display: PlatformDisplayAdapter): Builder {
            this.displayAdapter = display
            return this
        }

        fun dialogs(dialogs: PlatformDialogAdapter): Builder {
            this.dialogAdapter = dialogs
            return this
        }

        fun authBridge(bridge: PlatformAuthBridgeAdapter): Builder {
            this.bridgeAdapter = bridge
            return this
        }

        fun limbo(limbo: PlatformLimboAdapter): Builder {
            this.limboAdapter = limbo
            return this
        }

        fun proxy(proxy: Proxy): Builder {
            this.proxyAdapter = proxy
            return this
        }

        fun registerLimboProvider(provider: LimboServerProvider): Builder {
            this.limboProviders.add(provider)
            return this
        }

        fun build(): PnAuthBootstrap {
            val dataFolder = folder ?: throw IllegalStateException("dataFolder must be specified")
            val logger = platformLoggerAdapter ?: PlatformLoggerAdapter.of { println(it) }

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

                    val routeRegistered = proxyAdapter?.registerServerRoute(config.limbo.serverName, created.host(), created.port()) ?: false
                    if (!routeRegistered) {
                        limboAdapter?.registerRoute(config.limbo.serverName, created.host(), created.port())
                    }

                    proxySettings = proxySettings.requiringServerAuth()
                    logger.info("PicoLimbo embedded server route '${config.limbo.serverName}' active at ${created.host()}:${created.port()}.")
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
            var hubClient: HubApiClient? = null
            if (config.cluster.mode == ClusterMode.HUB) {
                hubClient = HubApiClient(config.cluster.hub)
                val authority = HubCredentialAuthority(hubClient)
                authService.installCredentialAuthority(authority)
                authService.installSecondFactorAuthority(authority)
                authService.installCentralAccountAuthority(authority)
            }

            val externalVerification = ExternalVerificationService(
                config.externalVerification, authService.extensions(), logger
            )
            try {
                externalVerification.start()
            } catch (error: Exception) {
                externalVerification.close()
                authService.close()
                limboServer?.close()
                proxyAdapter?.unregisterServerRoute(config.limbo.serverName)
                throw IllegalStateException("Не удалось запустить внешнее подтверждение.", error)
            }

            val clusterCoordinator = try {
                val clusterTransport = when (config.cluster.mode) {
                    ClusterMode.STANDALONE -> NoopClusterTransport
                    ClusterMode.SHARED_DATABASE -> DatabaseClusterTransport(
                        config.storage.url, config.storage.username, config.storage.password, config.cluster.nodeId
                    )
                    ClusterMode.REDIS -> RedisClusterTransport(
                        config.cluster.redis.uri, config.cluster.redis.stream, config.cluster.nodeId
                    )
                    ClusterMode.HUB -> HubClusterTransport(requireNotNull(hubClient), config.cluster.nodeId)
                }
                AuthClusterCoordinator(config.cluster.nodeId, authService, clusterTransport)
            } catch (error: Exception) {
                externalVerification.close()
                authService.close()
                limboServer?.close()
                proxyAdapter?.unregisterServerRoute(config.limbo.serverName)
                throw IllegalStateException("Не удалось запустить синхронизацию pnAuth.", error)
            }

            displayAdapter?.let { authService.installDisplay(it) }
            platformAdapter?.let { authService.installPlatform(it) }

            val messages = AuthMessages.load(dataFolder.resolve("messages"), config.locale, config.messageFormat)
            val bridge = bridgeAdapter ?: object : PlatformAuthBridgeAdapter {
                override fun authenticated(uniqueId: java.util.UUID) {}
                override fun loggedOut(uniqueId: java.util.UUID) {}
                override fun accountDeleted(uniqueId: java.util.UUID) {}
            }
            val migration = AuthMigrationService(repository)
            val commandService = AuthCommandService(authService, messages, bridge, migration, config.features)
            val accessService = AuthAccessService(authService, proxySettings, config.access, messages)
            val lifecycleCoordinator = AuthLifecycleCoordinator(authService, accessService)

            logger.info("pnAuth core services initialized successfully.")

            return PnAuthBootstrap(
                dataFolder, logger, config, proxySettings, limboServer,
                repository, authService, messages, bridge, migration,
                commandService, accessService, lifecycleCoordinator, platformAdapter, proxyAdapter
            ).also {
                it.externalVerification = externalVerification
                it.clusterCoordinator = clusterCoordinator
            }
        }
    }
}
