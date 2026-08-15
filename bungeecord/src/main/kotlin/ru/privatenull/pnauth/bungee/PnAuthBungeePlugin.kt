package ru.privatenull.pnauth.bungee

import com.github.retrooper.packetevents.PacketEvents
import net.md_5.bungee.api.plugin.Plugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.command.CommandRegistry
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap
import ru.privatenull.pnauth.dialog.PlayerDialogs
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
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer
import java.util.function.Function

class PnAuthBungeePlugin : Plugin() {
    private var auth: AuthService? = null
    private var commandRegistrar: BungeeCommandRegistrar? = null
    private var listener: BungeeAuthListener? = null
    private var dialogListener: BungeeDialogListener? = null
    private var authTasks: BungeeAuthTasks? = null
    private var migration: AuthMigrationService? = null
    private var limbo: LimboServer? = null
    private var playerDisplay: BungeePlayerDisplay? = null
    private var platform: BungeePlatform? = null
    private var playerDialogs: PlayerDialogs? = null
    private var dependencyReady: Boolean = false

    override fun onLoad() {
        try {
            val result = PacketEventsBootstrap.ensure(
                PacketEventsBootstrap.Platform.BUNGEECORD,
                dataFolder.toPath(), dataFolder.toPath().parent
            ) { message -> logger.info(message) }
            if (result == PacketEventsBootstrap.Result.INSTALLED_RESTART_REQUIRED) {
                dependencyRestartNotice()
                proxy.stop("PacketEvents installed by pnAuth; restart the proxy")
                return
            }
            dependencyReady = PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded
            if (!dependencyReady) {
                throw IllegalStateException("PacketEvents is not loaded; enable auto-install or install it manually")
            }
        } catch (exception: Exception) {
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }
    }

    override fun onEnable() {
        if (!dependencyReady) return
        try {
            val dataFolder: Path = getDataFolder().toPath()
            val defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize()
            val config = AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl)
            var proxySettings = config.proxy
            validateBackendTargets(proxySettings)
            val limboRegistry = LimboServerRegistry()
            limboRegistry.register(PicoLimboProvider())
            limbo = limboRegistry.create(config.limbo.provider, LimboServerContext(dataFolder, config.limbo))
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
                    limbo?.start()
                    val limboServer = limbo!!
                    proxy.servers[limboServer.id()] = proxy.constructServerInfo(
                        limboServer.id(),
                        InetSocketAddress(limboServer.host(), limboServer.port()),
                        "pnAuth authentication limbo", false
                    )
                    proxySettings = proxySettings.requiringServerAuth()
                } catch (exception: Exception) {
                    limbo?.close()
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
                    repository, TotpKeyStore.loadOrCreate(dataFolder.resolve("totp.key"))
                ), config.features
            )
            auth = authService
            val display = BungeePlayerDisplay(proxy, config.messageFormat)
            playerDisplay = display
            authService.installDisplay(display)
            val dialogs = PacketEventsPlayerDialogs(
                Function { uniqueId: UUID -> proxy.getPlayer(uniqueId) },
                Consumer { message: String -> logger.info(message) }
            )
            playerDialogs = dialogs
            val bPlatform = BungeePlatform(this, display, config.messageFormat, dialogs)
            platform = bPlatform
            authService.installPlatform(bPlatform)
            val messages = AuthMessages.load(dataFolder.resolve("messages"), config.locale, config.messageFormat)
            val actions = BungeeAuthActions(proxy, proxySettings, messages)
            migration = AuthMigrationService(repository)
            val commandService = AuthCommandService(authService, messages, actions, migration, config.features)
            val commandRegistry = CommandRegistry()
            commandRegistry.register(commandService)
            val access = AuthAccessService(authService, proxySettings, config.access, messages)
            val lifecycle = AuthLifecycleCoordinator(authService, access)
            val dListener = BungeeDialogListener(
                this, authService, commandService, messages, config.features,
                config.security.maxPasswordLength, proxySettings, bPlatform, commandRegistry
            )
            dialogListener = dListener
            val cRegistrar = BungeeCommandRegistrar(
                this, proxy.pluginManager, commandRegistry, messages.format()
            )
            commandRegistrar = cRegistrar
            cRegistrar.register()
            val aListener = BungeeAuthListener(
                proxy, this, lifecycle, messages, commandService, dListener
            )
            listener = aListener
            authTasks = BungeeAuthTasks(this, authService, messages, config.features, proxySettings)
        } catch (exception: Exception) {
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }

        val pluginManager = proxy.pluginManager
        pluginManager.registerListener(this, listener)
        pluginManager.registerListener(this, dialogListener)
        pluginManager.registerListener(this, authTasks)
        logger.info("pnAuth enabled for BungeeCord.")
    }

    override fun onDisable() {
        if (listener != null) {
            proxy.pluginManager.unregisterListener(listener)
        }
        if (dialogListener != null) {
            proxy.pluginManager.unregisterListener(dialogListener)
            dialogListener?.close()
        }
        if (authTasks != null) {
            proxy.pluginManager.unregisterListener(authTasks)
            authTasks?.close()
        }
        commandRegistrar?.close()
        auth?.close()
        playerDisplay?.close()
        val currentDialogs = playerDialogs
        if (currentDialogs is AutoCloseable) {
            try {
                currentDialogs.close()
            } catch (exception: Exception) {
                logger.warning("Could not close the pnAuth PacketEvents adapter: ${exception.message}")
            }
        }
        migration?.close()
        if (limbo != null) {
            proxy.servers.remove(limbo!!.id())
            limbo?.close()
        }
    }

    fun getApi(): AuthApi? = auth

    fun getKernel(): ExtensionKernel? = auth

    fun getPlatform(): PnPlatform? = platform

    private fun dependencyRestartNotice() {
        logger.warning("============================================================")
        logger.warning(" pnAuth FIRST-RUN SETUP")
        logger.warning(" PacketEvents was downloaded and SHA-256 verified successfully.")
        logger.warning(" The proxy is stopping intentionally so BungeeCord can load it.")
        logger.warning(" START THE PROXY ONE MORE TIME to finish enabling pnAuth.")
        logger.warning(" Automatic process restart requires an external server wrapper.")
        logger.warning(" Settings: plugins/pnAuth/dependencies.yml")
        logger.warning("============================================================")
    }

    private fun validateBackendTargets(settings: ProxySettings) {
        val targets = LinkedHashSet(settings.forcedHosts.values)
        if (settings.hasBackendServer()) targets.add(settings.backendServer)
        for (target in targets) {
            if (proxy.getServerInfo(target) == null) {
                throw IllegalArgumentException(
                    "Unknown backend server '$target'; register it in BungeeCord config before enabling pnAuth"
                )
            }
        }
    }
}
