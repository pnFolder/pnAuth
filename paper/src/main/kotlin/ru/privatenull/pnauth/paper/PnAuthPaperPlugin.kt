package ru.privatenull.pnauth.paper

import org.bukkit.plugin.java.JavaPlugin
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.Platform

/** Paper/Folia bootstrap. All reusable behavior remains in the shared module. */
class PnAuthPaperPlugin : JavaPlugin() {
    private var runtime: PnAuthPaperRuntime? = null

    override fun onEnable() {
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
