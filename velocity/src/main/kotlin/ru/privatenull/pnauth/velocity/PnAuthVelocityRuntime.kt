package ru.privatenull.pnauth.velocity

import com.github.retrooper.packetevents.PacketEvents
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.PnAuthHandle
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.PnAuthBootstrap
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.Proxy
import ru.privatenull.pnauth.platform.adapter.DeferredAuthBridgeAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformLoggerAdapter
import ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs
import ru.privatenull.pnauth.velocity.dialog.VelocityDialogCoordinator
import ru.privatenull.pnauth.velocity.proxy.VelocityProxyAdapter
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.function.Function

/**
 * Velocity runtime wiring for pnAuth.
 *
 * Keeps `PnAuthVelocityPlugin` minimal: the plugin delegates lifecycle and getters to this class.
 */
class PnAuthVelocityRuntime private constructor(
    private val owner: Any,
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val dataDirectory: Path
) : PnAuthHandle {
    private var bootstrap: PnAuthBootstrap? = null
    private var commandRegistrar: VelocityCommandRegistrar? = null
    private var actions: VelocityAuthActions? = null
    private var authTasks: VelocityAuthTasks? = null
    private var authListener: VelocityAuthListener? = null
    private var dialogs: VelocityDialogCoordinator? = null
    private var playerDisplay: VelocityPlayerDisplay? = null
    private var playerDialogs: PacketEventsPlayerDialogs? = null
    private var platform: VelocityPlatform? = null
    private var proxyAdapter: VelocityProxyAdapter? = null

    fun onProxyInitialization(retainedLimbo: ru.privatenull.pnauth.limbo.LimboServer? = null) {
        try {
            val dependency = PacketEventsBootstrap.ensure(
                PacketEventsBootstrap.Platform.VELOCITY,
                dataDirectory,
                dataDirectory.parent,
                logger::info
            )
            if (dependency == PacketEventsBootstrap.Result.INSTALLED_RESTART_REQUIRED) {
                PacketEventsBootstrap.logRestartNotice(PacketEventsBootstrap.Platform.VELOCITY, logger::warn)
                proxy.shutdown(Component.text("PacketEvents installed by pnAuth; restart the proxy"))
                return
            }
            if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isLoaded) {
                throw IllegalStateException("PacketEvents is not loaded; enable auto-install or install it manually")
            }
            val defaultUrl = "jdbc:sqlite:" + dataDirectory.resolve("auth.db").toAbsolutePath().normalize()
            val config = AuthConfig.load(dataDirectory.resolve("config.yml"), defaultUrl)
            validateServerTargets(config)

            val display = VelocityPlayerDisplay(proxy, config.messageFormat)
            playerDisplay = display

            val pDialogs = PacketEventsPlayerDialogs(
                Function { uniqueId -> proxy.getPlayer(uniqueId).orElse(null) }
            )
            playerDialogs = pDialogs

            val vPlatform = VelocityPlatform(owner, proxy, display, config.messageFormat, pDialogs)
            platform = vPlatform

            val proxyFacade = VelocityProxyAdapter(owner, proxy, logger, vPlatform)
            proxyAdapter = proxyFacade

            val bridge = DeferredAuthBridgeAdapter()

            val boot = PnAuthBootstrap.builder()
                .dataFolder(dataDirectory)
                .logger(PlatformLoggerAdapter.of { message -> logger.info(message) })
                .platform(vPlatform)
                .display(display)
                .dialogs(pDialogs)
                .proxy(proxyFacade)
                .authBridge(bridge)
                .retainedLimbo(retainedLimbo)
                .build()
            bootstrap = boot

            val vActions = VelocityAuthActions(proxy, boot.proxySettings, boot.messages, config.messageFormat, proxyFacade)
            actions = vActions
            bridge.bind(vActions)

            val vDialogs = VelocityDialogCoordinator(
                owner, proxy, boot.authService, boot.commandService, boot.messages,
                config.features, config.processingTitle, config.messageFormat,
                config.security.maxPasswordLength, boot.proxySettings, vPlatform
            )
            dialogs = vDialogs

            val vCmdRegistrar = VelocityCommandRegistrar(
                proxy, boot.commandService, boot.messages, ::reloadConfiguration
            )
            commandRegistrar = vCmdRegistrar
            vCmdRegistrar.register()

            val listener = VelocityAuthListener(
                proxy, boot.authService, boot.lifecycleCoordinator, config.messageFormat, null,
                boot.proxySettings, vDialogs, boot.messages, proxyFacade
            )
            authListener = listener
            proxy.eventManager.register(owner, listener)

            val tasks = VelocityAuthTasks(
                owner, proxy, boot.authService, boot.messages, config.features, boot.proxySettings,
                config.messageFormat, vDialogs
            )
            authTasks = tasks
            proxy.eventManager.register(owner, tasks)
        } catch (exception: Exception) {
            if (exception is IOException || exception is ProxyConfigurationException) {
                logger.error("pnAuth configuration error:\n${exception.message}")
                return
            }
            logger.error("pnAuth could not be initialized", exception)
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }

        logger.info("pnAuth enabled for Velocity via VelocityProxyAdapter architecture.")
    }

    fun onDisconnect(event: DisconnectEvent) {
        dialogs?.clearSession(event.player)
        bootstrap?.lifecycleCoordinator?.quit(event.player.uniqueId)
    }

    override fun close() {
        authListener?.let { proxy.eventManager.unregisterListener(owner, it) }
        authTasks?.let { proxy.eventManager.unregisterListener(owner, it) }
        bootstrap?.close()
        playerDisplay?.close()
        commandRegistrar?.close()
        authTasks?.close()
        dialogs?.close()
        playerDialogs?.close()
    }

    @Synchronized
    fun reloadConfiguration(): String {
        val defaultUrl = "jdbc:sqlite:" + dataDirectory.resolve("auth.db").toAbsolutePath().normalize()
        try {
            val candidate = AuthConfig.load(dataDirectory.resolve("config.yml"), defaultUrl)
            validateServerTargets(candidate)
            val active = bootstrap?.config
            if (active != null && candidate.limbo != active.limbo) {
                return message("config.reload.restart-required", mapOf("section" to "limbo"))
            }
        } catch (error: Exception) {
            return message("config.reload.invalid", mapOf("error" to (error.message ?: "ошибка проверки")))
        }
        return try {
            val retainedLimbo = bootstrap?.limbo
            closeForReload()
            onProxyInitialization(retainedLimbo)
            message("config.reload.success")
        } catch (error: Exception) {
            logger.error("pnAuth reload failed", error)
            message("config.reload.failed", mapOf("error" to (error.message ?: "неизвестная ошибка")))
        }
    }

    private fun closeForReload() {
        authListener?.let { proxy.eventManager.unregisterListener(owner, it) }
        authTasks?.let { proxy.eventManager.unregisterListener(owner, it) }
        bootstrap?.closeForReload()
        playerDisplay?.close()
        commandRegistrar?.close()
        authTasks?.close()
        dialogs?.close()
        playerDialogs?.close()
    }

    override fun api(): AuthApi = bootstrap?.authService
        ?: throw IllegalStateException("pnAuth is not enabled")

    override fun kernel(): ExtensionKernel = api()

    override fun platform(): Platform? = platform

    override fun proxy(): Proxy? = proxyAdapter

    fun proxyAdapter(): VelocityProxyAdapter? = proxyAdapter

    private fun message(key: String, replacements: Map<String, String> = emptyMap()): String =
        bootstrap?.messages?.text(key, replacements) ?: key

    private fun validateServerTargets(config: AuthConfig) {
        val errors = ArrayList<String>()
        val settings = config.proxy

        for (target in settings.getEffectiveAuthServers()) {
            val providedByEmbeddedLimbo = settings.serverType(target) == ru.privatenull.pnauth.config.ServerTargetType.LIMBO
            if (!providedByEmbeddedLimbo && proxy.getServer(target).isEmpty) {
                errors += "Auth server '$target' from servers.auth is not registered in velocity.toml. " +
                    "Register it there, remove it from servers.auth, or use the embedded Limbo route '${config.limbo.serverName}'."
            }
        }
        for (target in settings.getEffectiveBackendServers()) {
            if (proxy.getServer(target).isEmpty) {
                errors += "Backend server '$target' from servers.backend is not registered in velocity.toml. " +
                    "Register the lobby/backend server or remove it from servers.backend."
            }
        }
        for ((host, target) in settings.forcedHosts) {
            if (proxy.getServer(target).isEmpty) {
                errors += "Forced host '$host' points to unknown backend server '$target'. Register that server in velocity.toml."
            }
        }

        if (errors.isNotEmpty()) {
            throw ProxyConfigurationException(
                "Invalid server routing configuration:\n" + errors.joinToString("\n") { " - $it" }
            )
        }
    }

    private class ProxyConfigurationException(message: String) : IllegalArgumentException(message)

    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var owner: Any? = null
        private var proxy: ProxyServer? = null
        private var logger: Logger? = null
        private var dataDirectory: Path? = null

        fun owner(owner: Any): Builder {
            this.owner = owner
            return this
        }

        fun proxy(proxy: ProxyServer): Builder {
            this.proxy = proxy
            return this
        }

        fun logger(logger: Logger): Builder {
            this.logger = logger
            return this
        }

        fun dataDirectory(path: Path): Builder {
            this.dataDirectory = path
            return this
        }

        fun build(): PnAuthVelocityRuntime {
            val o = owner ?: throw IllegalStateException("owner must be specified")
            val p = proxy ?: throw IllegalStateException("proxy must be specified")
            val l = logger ?: throw IllegalStateException("logger must be specified")
            val d = dataDirectory ?: throw IllegalStateException("dataDirectory must be specified")
            return PnAuthVelocityRuntime(o, p, l, d)
        }
    }
}
