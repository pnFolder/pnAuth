package ru.privatenull.pnauth.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
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
    dependencies = [Dependency(id = "packetevents", optional = true)]
)
class PnAuthVelocityPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path
) {
    private var runtime: PnAuthVelocityRuntime? = null

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        val created = PnAuthVelocityRuntime.builder()
            .owner(this)
            .proxy(proxy)
            .logger(logger)
            .dataDirectory(dataDirectory)
            .build()
        runtime = created
        created.onProxyInitialization()
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        runtime?.onDisconnect(event)
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        runtime?.close()
    }

    fun getApi(): AuthApi? = runtime?.api()

    fun getKernel(): ExtensionKernel? = runtime?.kernel()

    fun getPlatform(): Platform? = runtime?.platform()

    fun getProxyAdapter(): VelocityProxyAdapter? = runtime?.proxyAdapter()
}
