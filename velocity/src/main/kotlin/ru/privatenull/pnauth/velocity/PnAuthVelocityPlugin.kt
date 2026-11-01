package ru.privatenull.pnauth.velocity

import ru.privatenull.pnauth.log.Log
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.pnfolder.pnlibrary.velocity.bootstrap.PnLibraryBootstrap
import org.slf4j.Logger
import ru.privatenull.pnauth.integration.PnLibraryRuntime
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.velocity.proxy.VelocityProxyAdapter
import java.nio.file.Path

@Plugin(
    id = "pnauth",
    name = "pnAuth",
    version = PnAuthBuild.VERSION,
    authors = ["privatenull"],
    dependencies = [Dependency(id = "packetevents", optional = true), Dependency(id = "pnlibrary", optional = true)]
)
class PnAuthVelocityPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path
) {
    private var runtime: PnAuthVelocityRuntime? = null
    private var pnLibrary: PnLibraryRuntime? = null

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        // A missing pnLibrary is downloaded for the next start; pnAuth itself keeps working without it.
        Log.fallback = Slf4jSink(logger)
        val pnLibraryReady = try {
            PnLibraryBootstrap.ensureInstalled(proxy, this, PnLibraryRuntime.MINIMUM_VERSION)
        } catch (failure: Throwable) {
            Log.warning("Не удалось подготовить pnLibrary; pnAuth работает без обновлений и диагностики.", failure)
            false
        }
        val created = PnAuthVelocityRuntime.builder()
            .owner(this)
            .proxy(proxy)
            .logger(logger)
            .dataDirectory(dataDirectory)
            .build()
        runtime = created
        created.onProxyInitialization()
        if (pnLibraryReady) connectPnLibrary()
    }

    private fun connectPnLibrary() {
        try {
            val connected = PnLibraryRuntime.connect(this)
            pnLibrary = connected
            connected.register(dataDirectory, "Velocity") { mapOf("version" to PnAuthBuild.VERSION) }
            connected.enabled("Velocity")
        } catch (failure: Throwable) {
            Log.warning("pnLibrary не подключена к pnAuth; плагин работает без неё.", failure)
            closePnLibrary()
        }
    }

    private fun closePnLibrary() {
        val closing = pnLibrary ?: return
        pnLibrary = null
        runCatching { closing.close() }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        runtime?.onDisconnect(event)
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        closePnLibrary()
        runtime?.close()
    }

    fun getApi(): AuthApi? = runtime?.api()

    fun getKernel(): ExtensionKernel? = runtime?.kernel()

    fun getPlatform(): Platform? = runtime?.platform()

    fun getProxyAdapter(): VelocityProxyAdapter? = runtime?.proxyAdapter()
}
