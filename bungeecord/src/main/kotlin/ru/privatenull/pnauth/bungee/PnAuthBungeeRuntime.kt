package ru.privatenull.pnauth.bungee

import com.github.retrooper.packetevents.PacketEvents
import net.md_5.bungee.api.ProxyServer
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.PnAuthHandle
import ru.privatenull.pnauth.bungee.proxy.BungeeProxyAdapter
import ru.privatenull.pnauth.command.CommandRegistry
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.PnAuthBootstrap
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.Proxy
import ru.privatenull.pnauth.platform.adapter.DeferredAuthBridgeAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformLoggerAdapter
import ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs
import java.nio.file.Path
import java.util.UUID
import java.util.function.Function

/**
 * BungeeCord runtime wiring for pnAuth.
 *
 * Keeps `PnAuthBungeePlugin` minimal: the plugin delegates lifecycle and getters to this class.
 */
class PnAuthBungeeRuntime private constructor(
    private val plugin: PnAuthBungeePlugin,
    private val proxyServer: ProxyServer
) : PnAuthHandle {
    private var bootstrap: PnAuthBootstrap? = null
    private var commandRegistrar: BungeeCommandRegistrar? = null
    private var listener: BungeeAuthListener? = null
    private var dialogListener: BungeeDialogListener? = null
    private var authTasks: BungeeAuthTasks? = null
    private var playerDisplay: BungeePlayerDisplay? = null
    private var playerDialogs: PacketEventsPlayerDialogs? = null
    private var platform: BungeePlatform? = null
    private var proxyAdapter: BungeeProxyAdapter? = null
    private var dependencyReady: Boolean = false

    fun onLoad(): Boolean {
        try {
            val result = PacketEventsBootstrap.ensure(
                PacketEventsBootstrap.Platform.BUNGEECORD,
                plugin.dataFolder.toPath(),
                plugin.dataFolder.toPath().parent
            ) { message -> plugin.logger.info(message) }
            if (result == PacketEventsBootstrap.Result.INSTALLED_RESTART_REQUIRED) {
                PacketEventsBootstrap.logRestartNotice(PacketEventsBootstrap.Platform.BUNGEECORD, plugin.logger::warning)
                proxyServer.stop("PacketEvents installed by pnAuth; restart the proxy")
                return false
            }
            dependencyReady = PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded
            if (!dependencyReady) {
                throw IllegalStateException("PacketEvents is not loaded; enable auto-install or install it manually")
            }
            return true
        } catch (exception: Exception) {
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }
    }

    fun onEnable() {
        if (!dependencyReady) return
        try {
            val dataFolder: Path = plugin.dataFolder.toPath()
            val defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize()
            val config = AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl)
            validateBackendTargets(config.proxy)

            val display = BungeePlayerDisplay(proxyServer, config.messageFormat)
            playerDisplay = display

            val dialogs = PacketEventsPlayerDialogs(
                Function { uniqueId: UUID -> proxyServer.getPlayer(uniqueId) }
            )
            playerDialogs = dialogs

            val bungeePlatform = BungeePlatform(plugin, display, config.messageFormat, dialogs)
            platform = bungeePlatform

            val proxyFacade = BungeeProxyAdapter(plugin, proxyServer, bungeePlatform)
            proxyAdapter = proxyFacade

            val bridge = DeferredAuthBridgeAdapter()

            val boot = PnAuthBootstrap.builder()
                .dataFolder(dataFolder)
                .logger(PlatformLoggerAdapter.of { message -> plugin.logger.info(message) })
                .platform(bungeePlatform)
                .display(display)
                .dialogs(dialogs)
                .proxy(proxyFacade)
                .authBridge(bridge)
                .build()
            bootstrap = boot

            bridge.bind(BungeeAuthActions(proxyServer, boot.proxySettings, boot.messages, proxyFacade))

            val commandRegistry = CommandRegistry()
            commandRegistry.register(boot.commandService)

            val dListener = BungeeDialogListener(
                plugin, boot.authService, boot.commandService, boot.messages, config.features,
                config.security.maxPasswordLength, boot.proxySettings, bungeePlatform, commandRegistry
            )
            dialogListener = dListener

            val cRegistrar = BungeeCommandRegistrar(
                plugin, proxyServer.pluginManager, commandRegistry, boot.messages.format()
            )
            commandRegistrar = cRegistrar
            cRegistrar.register()

            val aListener = BungeeAuthListener(
                proxyServer, plugin, boot.lifecycleCoordinator, boot.messages, boot.commandService, dListener
            )
            listener = aListener

            authTasks = BungeeAuthTasks(plugin, boot.authService, boot.messages, config.features, boot.proxySettings)

            val pluginManager = proxyServer.pluginManager
            pluginManager.registerListener(plugin, listener)
            pluginManager.registerListener(plugin, dialogListener)
            pluginManager.registerListener(plugin, authTasks)

            plugin.logger.info("pnAuth enabled for BungeeCord via BungeeProxyAdapter architecture.")
        } catch (exception: Exception) {
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }
    }

    override fun close() {
        if (listener != null) {
            proxyServer.pluginManager.unregisterListener(listener)
        }
        if (dialogListener != null) {
            proxyServer.pluginManager.unregisterListener(dialogListener)
            dialogListener?.close()
        }
        if (authTasks != null) {
            proxyServer.pluginManager.unregisterListener(authTasks)
            authTasks?.close()
        }
        commandRegistrar?.close()
        bootstrap?.close()
        playerDisplay?.close()
        playerDialogs?.close()
    }

    override fun api(): AuthApi = bootstrap?.authService
        ?: throw IllegalStateException("pnAuth is not enabled")

    override fun kernel(): ExtensionKernel = api()

    override fun platform(): Platform? = platform

    override fun proxy(): Proxy? = proxyAdapter

    fun proxyAdapter(): BungeeProxyAdapter? = proxyAdapter

    private fun validateBackendTargets(settings: ProxySettings) {
        val targets = LinkedHashSet(settings.forcedHosts.values)
        if (settings.hasBackendServer()) targets.add(settings.backendServer)
        for (target in targets) {
            if (proxyServer.getServerInfo(target) == null) {
                throw IllegalArgumentException(
                    "Unknown backend server '$target'; register it in BungeeCord config before enabling pnAuth"
                )
            }
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var plugin: PnAuthBungeePlugin? = null

        fun plugin(plugin: PnAuthBungeePlugin): Builder {
            this.plugin = plugin
            return this
        }

        fun build(): PnAuthBungeeRuntime {
            val p = plugin ?: throw IllegalStateException("plugin must be specified")
            return PnAuthBungeeRuntime(p, p.proxy)
        }
    }
}
