package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.plugin.Plugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.bungee.proxy.BungeeProxyAdapter
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.Platform

class PnAuthBungeePlugin : Plugin() {
    private var runtime: PnAuthBungeeRuntime? = null

    override fun onLoad() {
        val created = PnAuthBungeeRuntime.builder().plugin(this).build()
        runtime = if (created.onLoad()) created else null
    }

    override fun onEnable() {
        runtime?.onEnable()
    }

    override fun onDisable() {
        runtime?.close()
    }

    fun getApi(): AuthApi? = runtime?.api()

    fun getKernel(): ExtensionKernel? = runtime?.kernel()

    fun getPlatform(): Platform? = runtime?.platform()

    fun getProxyAdapter(): BungeeProxyAdapter? = runtime?.proxyAdapter()

    fun reloadConfiguration(): String = runtime?.reloadConfiguration()
        ?: "pnAuth ещё не инициализирован."
}
