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
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.PnAuthBootstrap
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.adapter.PlatformAuthBridgeAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformLoggerAdapter
import ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs
import ru.privatenull.pnauth.velocity.dialog.VelocityDialogCoordinator
import ru.privatenull.pnauth.velocity.proxy.VelocityProxyAdapter
import java.nio.file.Path
import java.util.UUID
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
    private var bootstrap: PnAuthBootstrap? = null
    private var commandRegistrar: VelocityCommandRegistrar? = null
    private var actions: VelocityAuthActions? = null
    private var authTasks: VelocityAuthTasks? = null
    private var dialogs: VelocityDialogCoordinator? = null
    private var playerDisplay: VelocityPlayerDisplay? = null
    private var playerDialogs: PacketEventsPlayerDialogs? = null
    private var platform: VelocityPlatform? = null
    private var proxyAdapter: VelocityProxyAdapter? = null

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
            validateBackendTargets(config.proxy)

            val display = VelocityPlayerDisplay(proxy, config.messageFormat)
            playerDisplay = display

            val pDialogs = PacketEventsPlayerDialogs(Function { uniqueId -> proxy.getPlayer(uniqueId).orElse(null) })
            playerDialogs = pDialogs

            val pForm = VelocityPlatform(this, proxy, display, config.messageFormat, pDialogs)
            platform = pForm

            val proxyFacade = VelocityProxyAdapter(this, proxy, logger, pForm)
            proxyAdapter = proxyFacade

            var vActions: VelocityAuthActions? = null

            // Fluent bootstrap using standardized PlatformAdapters and VelocityProxyAdapter
            val boot = PnAuthBootstrap.builder()
                .dataFolder(dataDirectory)
                .logger(PlatformLoggerAdapter.of { message -> logger.info(message) })
                .platform(pForm)
                .display(display)
                .dialogs(pDialogs)
                .proxy(proxyFacade)
                .authBridge(object : PlatformAuthBridgeAdapter {
                    override fun authenticated(uniqueId: UUID) { vActions?.authenticated(uniqueId) }
                    override fun authenticated(uniqueId: UUID, isRegistration: Boolean) { vActions?.authenticated(uniqueId, isRegistration) }
                    override fun authenticated(username: String) { vActions?.authenticated(username) }
                    override fun loggedOut(uniqueId: UUID) { vActions?.loggedOut(uniqueId) }
                    override fun accountDeleted(uniqueId: UUID) { vActions?.accountDeleted(uniqueId) }
                    override fun accountDeleted(username: String) { vActions?.accountDeleted(username) }
                    override fun broadcast(message: String) { vActions?.broadcast(message) }
                })
                .build()
            bootstrap = boot

            vActions = VelocityAuthActions(proxy, boot.proxySettings, boot.messages, config.messageFormat)
            actions = vActions

            val vDialogs = VelocityDialogCoordinator(
                proxy, boot.authService, boot.commandService, boot.messages,
                config.features, config.messageFormat, config.security.maxPasswordLength, boot.proxySettings, pForm
            )
            dialogs = vDialogs

            val vCmdRegistrar = VelocityCommandRegistrar(proxy, boot.commandService, config.messageFormat)
            commandRegistrar = vCmdRegistrar
            vCmdRegistrar.register()

            proxy.eventManager.register(
                this, VelocityAuthListener(
                    proxy, boot.authService, boot.lifecycleCoordinator, config.messageFormat, null,
                    boot.proxySettings, vDialogs, boot.messages
                )
            )
            val tasks = VelocityAuthTasks(
                this, proxy, boot.authService, boot.messages, config.features, boot.proxySettings,
                config.messageFormat, vDialogs
            )
            authTasks = tasks
            proxy.eventManager.register(this, tasks)
        } catch (exception: Exception) {
            logger.error("pnAuth could not be initialized", exception)
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }

        logger.info("pnAuth enabled for Velocity via VelocityProxyAdapter architecture.")
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        dialogs?.clearSession(event.player)
        bootstrap?.lifecycleCoordinator?.quit(event.player.uniqueId)
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        bootstrap?.close()
        playerDisplay?.close()
        commandRegistrar?.close()
        authTasks?.close()
        dialogs?.close()
        playerDialogs?.close()
    }

    fun getApi(): AuthApi? = bootstrap?.authService

    fun getKernel(): ExtensionKernel? = bootstrap?.authService

    fun getPlatform(): Platform? = platform

    fun getProxyAdapter(): VelocityProxyAdapter? = proxyAdapter

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

    private fun validateBackendTargets(settings: ProxySettings) {
        if (settings.hasBackendServer() && proxy.getServer(settings.backendServer).isEmpty) {
            throw IllegalArgumentException(
                "Unknown servers.backend-server '" + settings.backendServer + "'; register that server in velocity.toml"
            )
        }
        for (target in LinkedHashSet(settings.forcedHosts.values)) {
            if (proxy.getServer(target).isEmpty) {
                throw IllegalArgumentException(
                    "Unknown servers.forced-hosts target '" + target + "'; register that server in velocity.toml"
                )
            }
        }
    }
}
