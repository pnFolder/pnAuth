package ru.privatenull.pnauth.paper

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.Platform
import org.pnfolder.pnlibrary.bukkit.bootstrap.PnLibraryBootstrap
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap
import ru.privatenull.pnauth.integration.PnLibraryRuntime
import java.util.logging.Level

/** Paper/Folia bootstrap. All reusable behavior remains in the shared module. */
class PnAuthPaperPlugin : JavaPlugin() {
    private var runtime: PnAuthPaperRuntime? = null
    private var dependencyReady = false
    private var pnLibraryReady = false
    private var pnLibrary: PnLibraryRuntime? = null

    override fun onLoad() {
        // pnAuth keeps protecting the server even when pnLibrary cannot be prepared; only the shared services go missing.
        pnLibraryReady = try {
            PnLibraryBootstrap.ensureInstalled(this, PnLibraryRuntime.MINIMUM_VERSION)
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "Не удалось подготовить pnLibrary; pnAuth работает без обновлений и диагностики.", failure)
            false
        }
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
        connectPnLibrary()
    }

    private fun connectPnLibrary() {
        if (!pnLibraryReady) return
        try {
            val connected = PnLibraryRuntime.connect(this)
            pnLibrary = connected
            connected.register(dataFolder.toPath(), "Paper") {
                mapOf("version" to description.version, "folia" to isFolia())
            }
            connected.enabled("Paper")
            server.pluginManager.registerEvents(PnLibraryLifecycleListener(this), this)
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "pnLibrary не подключена к pnAuth; плагин работает без неё.", failure)
            closePnLibrary()
        }
    }

    internal fun closePnLibrary() {
        val closing = pnLibrary ?: return
        pnLibrary = null
        runCatching { closing.close() }
    }

    private fun isFolia(): Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    override fun onDisable() {
        closePnLibrary()
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
