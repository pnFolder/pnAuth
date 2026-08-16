package ru.privatenull.pnauth.bungee

import com.github.retrooper.packetevents.PacketEvents
import net.md_5.bungee.api.plugin.Plugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.command.CommandRegistry
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.PnAuthBootstrap
import ru.privatenull.pnauth.platform.PnPlatform
import ru.privatenull.pnauth.platform.adapter.PlatformAuthBridgeAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformLoggerAdapter
import ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer
import java.util.function.Function

class PnAuthBungeePlugin : Plugin() {
    private var bootstrap: PnAuthBootstrap? = null
    private var commandRegistrar: BungeeCommandRegistrar? = null
    private var listener: BungeeAuthListener? = null
    private var dialogListener: BungeeDialogListener? = null
    private var authTasks: BungeeAuthTasks? = null
    private var playerDisplay: BungeePlayerDisplay? = null
    private var playerDialogs: PacketEventsPlayerDialogs? = null
    private var platform: BungeePlatform? = null
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
            validateBackendTargets(config.proxy)

            val display = BungeePlayerDisplay(proxy, config.messageFormat)
            playerDisplay = display

            val dialogs = PacketEventsPlayerDialogs(
                Function { uniqueId: UUID -> proxy.getPlayer(uniqueId) },
                Consumer { message: String -> logger.info(message) }
            )
            playerDialogs = dialogs

            val bPlatform = BungeePlatform(this, display, config.messageFormat, dialogs)
            platform = bPlatform

            var actions: BungeeAuthActions? = null

            // Fluent bootstrap using standardized PlatformAdapters
            val boot = PnAuthBootstrap.builder()
                .dataFolder(dataFolder)
                .logger(PlatformLoggerAdapter.of { message -> logger.info(message) })
                .platform(bPlatform)
                .display(display)
                .dialogs(dialogs)
                .authBridge(object : PlatformAuthBridgeAdapter {
                    override fun authenticated(uniqueId: UUID) { actions?.authenticated(uniqueId) }
                    override fun authenticated(uniqueId: UUID, isRegistration: Boolean) { actions?.authenticated(uniqueId, isRegistration) }
                    override fun authenticated(username: String) { actions?.authenticated(username) }
                    override fun loggedOut(uniqueId: UUID) { actions?.loggedOut(uniqueId) }
                    override fun accountDeleted(uniqueId: UUID) { actions?.accountDeleted(uniqueId) }
                    override fun accountDeleted(username: String) { actions?.accountDeleted(username) }
                    override fun broadcast(message: String) { actions?.broadcast(message) }
                })
                .build()
            bootstrap = boot

            actions = BungeeAuthActions(proxy, boot.proxySettings, boot.messages)

            val commandRegistry = CommandRegistry()
            commandRegistry.register(boot.commandService)

            val dListener = BungeeDialogListener(
                this, boot.authService, boot.commandService, boot.messages, config.features,
                config.security.maxPasswordLength, boot.proxySettings, bPlatform, commandRegistry
            )
            dialogListener = dListener

            val cRegistrar = BungeeCommandRegistrar(
                this, proxy.pluginManager, commandRegistry, boot.messages.format()
            )
            commandRegistrar = cRegistrar
            cRegistrar.register()

            val aListener = BungeeAuthListener(
                proxy, this, boot.lifecycleCoordinator, boot.messages, boot.commandService, dListener
            )
            listener = aListener

            authTasks = BungeeAuthTasks(this, boot.authService, boot.messages, config.features, boot.proxySettings)
        } catch (exception: Exception) {
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }

        val pluginManager = proxy.pluginManager
        pluginManager.registerListener(this, listener)
        pluginManager.registerListener(this, dialogListener)
        pluginManager.registerListener(this, authTasks)
        logger.info("pnAuth enabled for BungeeCord via PlatformAdapter architecture.")
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
        bootstrap?.close()
        playerDisplay?.close()
        playerDialogs?.close()
    }

    fun getApi(): AuthApi? = bootstrap?.authService

    fun getKernel(): ExtensionKernel? = bootstrap?.authService

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
