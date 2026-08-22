package ru.privatenull.pnauth.paper

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap

/** Paper/Folia bootstrap. All reusable behavior remains in the shared module. */
class PnAuthPaperPlugin : JavaPlugin() {
    private var runtime: PnAuthPaperRuntime? = null
    private var dependencyReady = false

    override fun onLoad() {
        val result = PacketEventsBootstrap.ensure(
            PacketEventsBootstrap.Platform.PAPER,
            dataFolder.toPath(),
            dataFolder.toPath().parent
        ) { message -> logger.info(message) }
        if (result == PacketEventsBootstrap.Result.INSTALLED_RESTART_REQUIRED) {
            PacketEventsBootstrap.logRestartNotice(PacketEventsBootstrap.Platform.PAPER, logger::warning)
            Bukkit.shutdown()
            return
        }
        dependencyReady = true
    }

    override fun onEnable() {
        if (!dependencyReady) return
        val packetEvents = server.pluginManager.getPlugin("packetevents")
            ?: throw IllegalStateException("PacketEvents is not loaded; enable auto-install or install it manually")
        if (!packetEvents.isEnabled) {
            throw IllegalStateException("PacketEvents must be enabled before pnAuth")
        }
        val created = PnAuthPaperRuntime.builder().plugin(this).build()
        runtime = created
        created.onEnable()
    }

    override fun onDisable() {
        runtime?.close()
    }

    /** Returns the authentication API for plugins which explicitly need auth operations. */
    fun getApi(): AuthApi? = runtime?.api()

    /** Returns the generic extension kernel. */
    fun getKernel(): ExtensionKernel? = runtime?.kernel()

    /** Returns the platform-neutral player API. */
    fun getPlatform(): Platform? = runtime?.platform()

    fun reloadConfiguration(): String = runtime?.reloadConfiguration()
        ?: "pnAuth ещё не инициализирован."
}
