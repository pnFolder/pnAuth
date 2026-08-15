package ru.privatenull.pnauth.velocity

import com.github.retrooper.packetevents.PacketEvents
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.limbo.LimboServer
import ru.privatenull.pnauth.limbo.LimboServerContext
import ru.privatenull.pnauth.limbo.LimboServerRegistry
import ru.privatenull.pnauth.limbo.PicoLimboProvider
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.platform.PnPlatform
import ru.privatenull.pnauth.policy.AuthAccessService
import ru.privatenull.pnauth.security.TotpKeyStore
import ru.privatenull.pnauth.security.TotpService
import ru.privatenull.pnauth.service.AuthService
import ru.privatenull.pnauth.storage.AuthMigrationService
import ru.privatenull.pnauth.storage.JdbcAuthRepository
import ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs
import ru.privatenull.pnauth.velocity.dialog.VelocityDialogCoordinator
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.function.Function

@Plugin(
    id = "pnauth",
    name = "pnAuth",
    version = PnAuthBuild.VERSION,
    authors = ["privatenull"],
    dependencies = [Dependency(id = "packetevents", optional = true)]
)
class PnAuthVelocityPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path
) {
    private var auth: AuthService? = null
    private var commandRegistrar: VelocityCommandRegistrar? = null
    private var messages: AuthMessages? = null
    private var actions: VelocityAuthActions? = null
    private var authTasks: VelocityAuthTasks? = null
    private var migration: AuthMigrationService? = null
    private var limbo: LimboServer? = null
    private var limboServer: RegisteredServer? = null
    private var dialogs: VelocityDialogCoordinator? = null
    private var lifecycle: AuthLifecycleCoordinator? = null
    private var playerDisplay: VelocityPlayerDisplay? = null
    private var platform: VelocityPlatform? = null
    private var playerDialogs: PacketEventsPlayerDialogs? = null

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        try {
            val dependency = PacketEventsBootstrap.ensure(
                PacketEventsBootstrap.Platform.VELOCITY, dataDirectory, dataDirectory.parent,
                logger::info
            )
            if (dependency == PacketEventsBootstrap.Result.INSTALLED_RESTART_REQUIRED) {
                dependencyRestartNotice()
                proxy.shutdown(Component.text("PacketEvents installed by pnAuth; restart the proxy"))
                return
            }
            if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isLoaded) {
                throw IllegalStateException("PacketEvents is not loaded; enable auto-install or install it manually")
            }
            val defaultUrl = "jdbc:sqlite:" + dataDirectory.resolve("auth.db").toAbsolutePath().normalize()
            val config = AuthConfig.load(dataDirectory.resolve("config.yml"), defaultUrl)
            var proxySettings: ProxySettings = config.proxy
            if (proxySettings.hasBackendServer() && proxy.getServer(proxySettings.backendServer).isEmpty) {
                throw IllegalArgumentException(
                    "Unknown servers.backend-server '" + proxySettings.backendServer + "'; register that server in velocity.toml"
                )
            }
            for (target in LinkedHashSet(proxySettings.forcedHosts.values)) {
                if (proxy.getServer(target).isEmpty) {
                    throw IllegalArgumentException(
                        "Unknown servers.forced-hosts target '" + target + "'; register that server in velocity.toml"
                    )
                }
            }
            val limboRegistry = LimboServerRegistry()
            limboRegistry.register(PicoLimboProvider())
            val createdLimbo = limboRegistry.create(config.limbo.provider, LimboServerContext(dataDirectory, config.limbo))
            limbo = createdLimbo
            if (config.limbo.enabled) {
                if (!config.proxy.authServer.equals(config.limbo.serverName, ignoreCase = true)) {
                    throw IllegalArgumentException("proxy.auth-server must equal limbo.server-name when limbo is enabled")
                }
                if (config.proxy.backendServer.equals(config.limbo.serverName, ignoreCase = true)) {
                    throw IllegalArgumentException(
                        "servers.backend-server must differ from limbo.server-name; auth and backend cannot share a name"
                    )
                }
                try {
                    createdLimbo.start()
                    limboServer = proxy.registerServer(
                        ServerInfo(
                            createdLimbo.id(), InetSocketAddress(createdLimbo.host(), createdLimbo.port())
                        )
                    )
                    logger.info(
                        "Registered embedded auth route '{}' at {}:{}; authenticated players route to '{}'.",
                        createdLimbo.id(), createdLimbo.host(), createdLimbo.port(), config.proxy.backendServer
                    )
                    proxySettings = proxySettings.requiringServerAuth()
                } catch (exception: Exception) {
                    createdLimbo.close()
                    limbo = null
                    throw IllegalStateException(
                        "Embedded PicoLimbo is enabled but could not be started. " +
                                "pnAuth refuses to continue with an unsecured authentication route.", exception
                    )
                }
            }
            val repository = JdbcAuthRepository(
                config.storage.url,
                config.storage.username,
                config.storage.password
            )
            val authService = AuthService(
                repository, config.security, TotpService(
                    repository, TotpKeyStore.loadOrCreate(dataDirectory.resolve("totp.key"))
                ), config.features
            )
            auth = authService
            val display = VelocityPlayerDisplay(proxy, config.messageFormat)
            playerDisplay = display
            authService.installDisplay(display)
            val pDialogs = PacketEventsPlayerDialogs(Function { uniqueId -> proxy.getPlayer(uniqueId).orElse(null) })
            playerDialogs = pDialogs
            val pForm = VelocityPlatform(this, proxy, display, config.messageFormat, pDialogs)
            platform = pForm
            authService.installPlatform(pForm)
            val authMessages = AuthMessages.load(dataDirectory.resolve("messages"), config.locale, config.messageFormat)
            messages = authMessages
            val velocityActions = VelocityAuthActions(proxy, proxySettings, authMessages, config.messageFormat)
            actions = velocityActions
            val authMigration = AuthMigrationService(repository)
            migration = authMigration
            val commandService = AuthCommandService(authService, authMessages, velocityActions, authMigration, config.features)
            val vDialogs = VelocityDialogCoordinator(
                proxy, logger, authService, commandService, authMessages,
                config.features, config.messageFormat, config.security.maxPasswordLength, proxySettings
            )
            dialogs = vDialogs
            val access = AuthAccessService(authService, proxySettings, config.access, authMessages)
            val vLifecycle = AuthLifecycleCoordinator(authService, access)
            lifecycle = vLifecycle
            val vCmdRegistrar = VelocityCommandRegistrar(proxy, commandService, config.messageFormat)
            commandRegistrar = vCmdRegistrar
            vCmdRegistrar.register()
            proxy.eventManager.register(
                this, VelocityAuthListener(
                    proxy, authService, vLifecycle, config.messageFormat, limboServer,
                    proxySettings, vDialogs, authMessages
                )
            )
            val tasks = VelocityAuthTasks(
                this, proxy, authService, authMessages, config.features, proxySettings,
                config.messageFormat, vDialogs
            )
            authTasks = tasks
            proxy.eventManager.register(this, tasks)
        } catch (exception: Exception) {
            logger.error("pnAuth could not be initialized", exception)
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }

        logger.info("pnAuth enabled for Velocity.")
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        dialogs?.clearSession(event.player)
        val currentAuth = auth
        if (currentAuth != null) {
            lifecycle?.quit(event.player.uniqueId)
        }
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        auth?.close()
        playerDisplay?.close()
        migration?.close()
        limboServer?.let { server ->
            proxy.unregisterServer(server.serverInfo)
        }
        limbo?.close()
        commandRegistrar?.close()
        authTasks?.close()
        dialogs?.close()
        playerDialogs?.close()
    }

    fun getApi(): AuthApi? = auth

    fun getKernel(): ExtensionKernel? = auth

    /** Returns the platform-neutral player API. */
    fun getPlatform(): PnPlatform? = platform

    private fun dependencyRestartNotice() {
        logger.warn("============================================================")
        logger.warn(" pnAuth FIRST-RUN SETUP")
        logger.warn(" PacketEvents was downloaded and SHA-256 verified successfully.")
        logger.warn(" The proxy is stopping intentionally so Velocity can load it.")
        logger.warn(" START THE PROXY ONE MORE TIME to finish enabling pnAuth.")
        logger.warn(" Automatic process restart requires an external server wrapper.")
        logger.warn(" Settings: plugins/pnAuth/dependencies.yml")
        logger.warn("============================================================")
    }
}
