package ru.privatenull.pnauth.integration

import org.pnfolder.pnlibrary.api.diagnostics.DiagnosticConfiguration
import org.pnfolder.pnlibrary.api.diagnostics.DiagnosticContainer
import org.pnfolder.pnlibrary.api.plugin.ModuleContext
import org.pnfolder.pnlibrary.api.plugin.PluginRegistration
import org.pnfolder.pnlibrary.api.remote.PolicyErrorAction
import org.pnfolder.pnlibrary.api.remote.RemotePolicySettings
import org.pnfolder.pnlibrary.api.runtime.PnLibrary
import org.pnfolder.pnlibrary.api.runtime.PnLibraryIdentity
import org.pnfolder.pnlibrary.api.runtime.PnLibraryProvider
import org.pnfolder.pnlibrary.api.updates.UpdateChannel
import java.nio.file.Path
import java.util.function.Supplier

/**
 * pnLibrary integration shared by BungeeCord, Velocity and Paper. Loaded only after the pnLibrary bootstrap
 * confirmed that a suitable runtime is installed; any failure here must never stop pnAuth itself.
 */
class PnLibraryRuntime private constructor(
    private val owner: Any,
    private val runtime: PnLibrary
) : AutoCloseable {
    private var registration: PluginRegistration? = null
    private var module: ModuleContext? = null

    /** Registers pnAuth with the policy, diagnostics and update services. */
    fun register(dataFolder: Path, platform: String, snapshot: Supplier<Map<String, Any?>>) {
        check(registration == null) { "pnAuth уже зарегистрирован в pnLibrary" }
        val created = runtime.plugins.register(owner)
        registration = created
        try {
            module = created.registerModule(MODULE_ID) { builder ->
                // bStats держит отдельный проект на каждую платформу; без ID платформы метрики не включаются.
                BSTATS_PROJECT_IDS[platform]?.let { id -> builder.metrics { metrics -> metrics.bStats(id).fastStats(FASTSTATS_TOKEN) } }
                builder
                    // A policy that cannot be checked (offline, JRE without a compiler) never stops pnAuth;
                    // only an explicit denial from the policy does.
                    .remotePolicy(
                        RemotePolicySettings.builder()
                            .source(POLICY_SOURCE)
                            .onError(PolicyErrorAction.KEEP_RUNNING)
                            .support(PnLibraryIdentity.SUPPORT_URL)
                            .build()
                    )
                    .diagnostics(
                        dataFolder,
                        DiagnosticContainer.builder(MODULE_ID)
                            .value("platform", platform)
                            .snapshot(snapshot)
                            .configuration(
                                DiagnosticConfiguration.builder("config.yml")
                                    .secretKeyRegex(SECRET_KEYS)
                                    .build()
                            )
                            .build()
                    )
                    .updates("pnFolder", "pnAuth") { updater ->
                        updater
                            .channel(UpdateChannel.STABLE)
                            .automaticDownload(false)
                            .artifact(ARTIFACT_PATTERN, 17)
                    }
            }
        } catch (failure: RuntimeException) {
            closeRegistration()
            throw failure
        }
    }

    /** Prints the "enabled" card in the console. */
    fun enabled(platform: String) {
        module?.lifecycle?.enabled()
            ?.ok("Платформа", platform)
            ?.show()
    }

    override fun close() {
        val closing = module
        module = null
        if (closing != null && !closing.isClosed) {
            runCatching { closing.lifecycle.disabled().show() }
        }
        closeRegistration()
    }

    private fun closeRegistration() {
        val closing = registration
        registration = null
        if (closing != null && !closing.isClosed) closing.close()
    }

    companion object {
        const val MINIMUM_VERSION = "0.0.1-dev.6"
        private const val MODULE_ID = "pnauth"
        // Ключ - название платформы, которое передаёт плагин; у каждой платформы свой проект в bStats.
        private val BSTATS_PROJECT_IDS: Map<String, Int> = mapOf("Paper" to 35046, "BungeeCord" to 35051, "Velocity" to 35052)
        private const val FASTSTATS_TOKEN = "0ae096b755bac35d7d14ed4c3e47110e"
        private const val POLICY_SOURCE =
            "https://raw.githubusercontent.com/pnFolder/pnlibrary-policy/main/.pnlibrary/policies/PnAuthPolicy.java"
        private const val ARTIFACT_PATTERN = "(?i)^pnAuth-[0-9].*[.]jar$"
        private const val SECRET_KEYS =
            "(?i).*(password|passwd|secret|token|key|salt|uri|url|user(name)?|webhook|api)$"

        @JvmStatic
        fun connect(owner: Any): PnLibraryRuntime {
            val runtime = PnLibraryProvider.get()
            check(runtime.isAtLeastVersion(MINIMUM_VERSION)) {
                "pnAuth требует pnLibrary $MINIMUM_VERSION или новее, установлена ${runtime.version}"
            }
            return PnLibraryRuntime(owner, runtime)
        }
    }
}
