package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.plugin.Plugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.bungee.proxy.BungeeProxyAdapter
import org.pnfolder.pnlibrary.bungee.bootstrap.PnLibraryBootstrap
import ru.privatenull.pnauth.integration.PnLibraryRuntime
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.Platform
import java.util.logging.Level

class PnAuthBungeePlugin : Plugin() {
    private var runtime: PnAuthBungeeRuntime? = null
    private var pnLibraryReady = false
    private var pnLibrary: PnLibraryRuntime? = null

    override fun onLoad() {
        // A missing pnLibrary is downloaded for the next start; pnAuth itself keeps working without it.
        pnLibraryReady = try {
            PnLibraryBootstrap.ensureInstalled(this, PnLibraryRuntime.MINIMUM_VERSION)
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "Не удалось подготовить pnLibrary; pnAuth работает без обновлений и диагностики.", failure)
            false
        }
        val created = PnAuthBungeeRuntime.builder().plugin(this).build()
        runtime = if (created.onLoad()) created else null
    }

    override fun onEnable() {
        runtime?.onEnable()
        if (runtime != null) connectPnLibrary()
    }

    private fun connectPnLibrary() {
        if (!pnLibraryReady) return
        try {
            val connected = PnLibraryRuntime.connect(this)
            pnLibrary = connected
            connected.register(dataFolder.toPath(), "BungeeCord") { mapOf("version" to description.version) }
            connected.enabled("BungeeCord")
        } catch (failure: Throwable) {
            logger.log(Level.WARNING, "pnLibrary не подключена к pnAuth; плагин работает без неё.", failure)
            closePnLibrary()
        }
    }

    private fun closePnLibrary() {
        val closing = pnLibrary ?: return
        pnLibrary = null
        runCatching { closing.close() }
    }

    override fun onDisable() {
        closePnLibrary()
        runtime?.close()
    }

    fun getApi(): AuthApi? = runtime?.api()

    fun getKernel(): ExtensionKernel? = runtime?.kernel()

    fun getPlatform(): Platform? = runtime?.platform()

    fun getProxyAdapter(): BungeeProxyAdapter? = runtime?.proxyAdapter()

    fun reloadConfiguration(): String = runtime?.reloadConfiguration()
        ?: "pnAuth ещё не инициализирован."
}
