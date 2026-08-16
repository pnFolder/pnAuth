package ru.privatenull.pnauth.paper

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.config.AuthConfig
import ru.privatenull.pnauth.config.PaperSettings
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.security.TotpKeyStore
import ru.privatenull.pnauth.security.TotpService
import ru.privatenull.pnauth.service.AuthService
import ru.privatenull.pnauth.storage.AuthMigrationService
import ru.privatenull.pnauth.storage.JdbcAuthRepository
import java.nio.file.Path

/** Paper/Folia bootstrap. All reusable behavior remains in the shared module. */
class PnAuthPaperPlugin : JavaPlugin(), Listener {
    private var auth: AuthService? = null
    private var display: PaperPlayerDisplay? = null
    private var platform: PaperPlatform? = null
    private var dialogs: PaperPlayerDialogs? = null
    private var migration: AuthMigrationService? = null
    private var paperSettings: PaperSettings? = null

    override fun onEnable() {
        try {
            val dataFolder: Path = getDataFolder().toPath()
            val defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize()
            val config = AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl)
            paperSettings = config.paper
            val repository = JdbcAuthRepository(
                config.storage.url, config.storage.username, config.storage.password
            )

            val authService = AuthService(
                repository, config.security, TotpService(
                    repository, TotpKeyStore.loadOrCreate(dataFolder.resolve("totp.key"))
                ), config.features
            )
            auth = authService
            val paperDisplay = PaperPlayerDisplay(this)
            display = paperDisplay
            val paperDialogs = PaperPlayerDialogs(this)
            dialogs = paperDialogs
            val paperPlatform = PaperPlatform(this, paperDisplay, paperDialogs)
            platform = paperPlatform
            authService.installDisplay(paperDisplay)
            authService.installPlatform(paperPlatform)

            val messages = AuthMessages.load(
                dataFolder.resolve("messages"), config.locale, config.messageFormat
            )
            migration = AuthMigrationService(repository)
            val commandService = AuthCommandService(
                authService, messages, PaperAuthActions(this, messages), migration, config.features
            )
            val commandAdapter = PaperAuthCommand(commandService)
            commandService.definitions().forEach { definition ->
                val command = getCommand(definition.name)
                    ?: throw IllegalStateException("Missing command declaration: " + definition.name)
                command.setExecutor(commandAdapter)
                command.tabCompleter = commandAdapter
            }

            server.pluginManager.registerEvents(this, this)
            server.pluginManager.registerEvents(PaperAccessListener(authService, config.paper), this)
            logger.info("pnAuth enabled for ${paperPlatform.type()}.")
        } catch (exception: Exception) {
            throw IllegalStateException("pnAuth could not be initialized", exception)
        }
    }

    override fun onDisable() {
        display?.close()
        dialogs?.close()
        auth?.close()
        migration?.close()
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val ip = player.address?.address?.hostAddress
        auth?.onJoin(player.uniqueId, player.name, ip)
        // Teleportation is player-bound and therefore safe on both Paper and Folia.
        // It happens before authentication completes, so the configured movement gate applies immediately.
        tryTeleport(player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        auth?.onQuit(event.player.uniqueId)
    }

    /** Returns the authentication API for plugins which explicitly need auth operations. */
    fun getApi(): AuthApi? = auth

    /** Returns the generic extension kernel. */
    fun getKernel(): ExtensionKernel? = auth

    /** Returns the platform-neutral player API. */
    fun getPlatform(): Platform? = platform

    private fun tryTeleport(player: Player) {
        val settings = paperSettings
        if (settings == null || !settings.teleportEnabled) return
        val world = server.getWorld(settings.world)
        if (world == null) {
            logger.warning("Paper authentication world is not loaded: " + settings.world)
            return
        }
        val target = Location(
            world, settings.x, settings.y, settings.z,
            settings.yaw, settings.pitch
        )
        player.scheduler.run(this, { player.teleportAsync(target) }, null)
    }
}
