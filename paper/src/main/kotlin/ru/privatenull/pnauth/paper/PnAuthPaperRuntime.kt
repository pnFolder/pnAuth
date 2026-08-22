package ru.privatenull.pnauth.paper

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.PnAuthHandle
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.PaperSettings
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.command.CommandRegistry
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.platform.PnAuthBootstrap
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.Proxy
import ru.privatenull.pnauth.platform.adapter.DeferredAuthBridgeAdapter
import ru.privatenull.pnauth.platform.adapter.PlatformLoggerAdapter
import ru.privatenull.pnauth.ui.AuthUiCoordinator
import ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

/**
 * Paper/Folia runtime wiring for pnAuth.
 *
 * Uses the shared `PnAuthBootstrap` builder to keep initialization consistent across platforms.
 */
class PnAuthPaperRuntime private constructor(
    private val plugin: JavaPlugin
) : PnAuthHandle, Listener {
    private var bootstrap: PnAuthBootstrap? = null
    private var display: PaperPlayerDisplay? = null
    private var platform: PaperPlatform? = null
    private var dialogs: PacketEventsPlayerDialogs? = null
    private var paperSettings: PaperSettings? = null
    private var accessListener: PaperAccessListener? = null
    private var uiCoordinator: AuthUiCoordinator? = null
    private var commandRegistration: AutoCloseable? = null
    private var runtimeMessages: AuthMessages? = null
    private val originalLocations = ConcurrentHashMap<UUID, Location>()

    fun onEnable() {
        try {
            val dataFolder: Path = plugin.dataFolder.toPath()
            val defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize()
            val config = AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl)
            paperSettings = config.paper

            val paperDisplay = PaperPlayerDisplay(plugin, config.messageFormat)
            display = paperDisplay
            val paperDialogs = PacketEventsPlayerDialogs(
                Function { uniqueId: UUID -> plugin.server.getPlayer(uniqueId) }
            ) { message -> plugin.logger.warning(message) }
            dialogs = paperDialogs
            val paperPlatform = PaperPlatform(plugin, paperDisplay, paperDialogs, config.messageFormat)
            platform = paperPlatform

            val messages = AuthMessages.load(
                dataFolder.resolve("messages"),
                config.locale,
                config.messageFormat
            )

            // Paper side-effects requested by shared authentication events
            val actions = PaperAuthActions(plugin, messages, ::teleportAfterAuthentication)
            val bridge = DeferredAuthBridgeAdapter().apply { bind(actions) }

            val boot = PnAuthBootstrap.builder()
                .dataFolder(dataFolder)
                .logger(PlatformLoggerAdapter.of { message -> plugin.logger.info(message) })
                .platform(paperPlatform)
                .display(paperDisplay)
                .dialogs(paperDialogs)
                .authBridge(bridge)
                .build()
            bootstrap = boot
            runtimeMessages = boot.messages

            // Commands
            val commandRegistry = CommandRegistry()
            commandRegistration = commandRegistry.register(boot.commandService)
            val ui = AuthUiCoordinator(
                boot.authService,
                boot.commandService,
                boot.messages,
                config.features,
                config.security.maxPasswordLength,
                plugin.server.name,
                paperPlatform,
                PaperAuthUiRenderer(boot.messages),
                commandRegistry
            ) { }
            uiCoordinator = ui

            val commandAdapter = PaperAuthCommand(plugin, commandRegistry, boot.messages, ::reloadConfiguration)
            commandRegistry.definitions().forEach { definition ->
                val command = plugin.getCommand(definition.name)
                    ?: throw IllegalStateException("Missing command declaration: " + definition.name)
                command.setExecutor(commandAdapter)
                command.tabCompleter = commandAdapter
            }

            // Listeners
            plugin.server.pluginManager.registerEvents(this, plugin)
            val access = PaperAccessListener(boot.authService, config.paper)
            accessListener = access
            plugin.server.pluginManager.registerEvents(access, plugin)

            plugin.logger.info("pnAuth enabled for ${paperPlatform.type()}.")
        } catch (exception: Exception) {
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }
    }

    override fun close() {
        HandlerList.unregisterAll(this)
        accessListener?.let(HandlerList::unregisterAll)
        originalLocations.clear()
        uiCoordinator?.close()
        commandRegistration?.close()
        display?.close()
        dialogs?.close()
        bootstrap?.close()
        uiCoordinator = null
        commandRegistration = null
        bootstrap = null
        display = null
        dialogs = null
        platform = null
        runtimeMessages = null
    }

    @Synchronized
    fun reloadConfiguration(): String {
        val dataFolder = plugin.dataFolder.toPath()
        val messagesBeforeReload = runtimeMessages
        val defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize()
        try {
            AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl)
        } catch (error: Exception) {
            return messagesBeforeReload?.text(
                "config.reload.invalid", mapOf("error" to (error.message ?: "ошибка проверки"))
            ) ?: "Конфигурация pnAuth не перезагружена."
        }
        return try {
            close()
            onEnable()
            requireNotNull(runtimeMessages).text("config.reload.success")
        } catch (error: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "pnAuth reload failed", error)
            (runtimeMessages ?: messagesBeforeReload)?.text(
                "config.reload.failed", mapOf("error" to (error.message ?: "неизвестная ошибка"))
            ) ?: "Не удалось применить конфигурацию pnAuth."
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        originalLocations[player.uniqueId] = player.location.clone()
        val ip = player.address?.address?.hostAddress
        val coordinator = uiCoordinator
        val renderer = PaperAuthUiRenderer(requireNotNull(bootstrap).messages)
        bootstrap?.authService?.onJoin(player.uniqueId, player.name, ip)?.whenComplete { status, error ->
            if (error != null || status == null || !player.isConnected) return@whenComplete
            player.scheduler.run(plugin, {
                if (!player.isConnected || uiCoordinator !== coordinator) return@run
                val shown = coordinator?.show(player.uniqueId, status, player.protocolVersion) == true
                if (!shown && status != ru.privatenull.pnauth.api.AuthStatus.AUTHENTICATED) {
                    player.sendMessage(renderer.renderText(requireNotNull(bootstrap).messages.prompt(status)))
                }
            }, null)
        }
        tryTeleport(player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        originalLocations.remove(event.player.uniqueId)
        uiCoordinator?.clearSession(event.player.uniqueId)
        bootstrap?.authService?.onQuit(event.player.uniqueId)
    }

    override fun api(): AuthApi = bootstrap?.authService
        ?: throw IllegalStateException("pnAuth is not enabled")

    override fun kernel(): ExtensionKernel = api()

    override fun platform(): Platform? = platform

    override fun proxy(): Proxy? = null

    private fun tryTeleport(player: Player) {
        val settings = paperSettings
        if (settings == null || !settings.teleportEnabled) return
        val world = plugin.server.getWorld(settings.world)
        if (world == null) {
            plugin.logger.warning("Paper authentication world is not loaded: " + settings.world)
            return
        }
        val target = Location(
            world,
            settings.x,
            settings.y,
            settings.z,
            settings.yaw,
            settings.pitch
        )
        player.scheduler.run(plugin, { player.teleportAsync(target) }, null)
    }

    private fun teleportAfterAuthentication(playerId: UUID) {
        val player = plugin.server.getPlayer(playerId) ?: return
        val settings = paperSettings ?: return
        val target = when (settings.successDestination) {
            PaperSettings.SuccessDestination.ORIGINAL -> originalLocations.remove(playerId)
            PaperSettings.SuccessDestination.SPAWN -> plugin.server.getWorld(settings.successWorld)?.spawnLocation
            PaperSettings.SuccessDestination.CUSTOM -> plugin.server.getWorld(settings.successWorld)?.let { world ->
                Location(
                    world, settings.successX, settings.successY, settings.successZ,
                    settings.successYaw, settings.successPitch
                )
            }
            PaperSettings.SuccessDestination.NONE -> null
        }
        if (settings.successDestination != PaperSettings.SuccessDestination.ORIGINAL) {
            originalLocations.remove(playerId)
        }
        if (target == null) {
            if (settings.successDestination != PaperSettings.SuccessDestination.NONE) {
                plugin.logger.warning("Paper success teleport destination is unavailable for ${player.name}.")
            }
            return
        }
        val ticks = ((settings.successDelayMillis + 49L) / 50L).coerceAtLeast(1L)
        player.scheduler.runDelayed(plugin, { player.teleportAsync(target) }, null, ticks)
    }

    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var plugin: JavaPlugin? = null

        fun plugin(plugin: JavaPlugin): Builder {
            this.plugin = plugin
            return this
        }

        fun build(): PnAuthPaperRuntime {
            val p = plugin ?: throw IllegalStateException("plugin must be specified")
            return PnAuthPaperRuntime(p)
        }
    }
}
