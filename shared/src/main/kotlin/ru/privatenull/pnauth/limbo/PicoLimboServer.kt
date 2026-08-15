package ru.privatenull.pnauth.limbo

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.jna.Pointer
import ru.privatenull.pnauth.config.LimboSettings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PicoLimboServer(
    dataDirectory: Path,
    private val settings: LimboSettings
) : LimboServer {

    private val dataDir: Path = dataDirectory.resolve("limbo")
    private val nativeLock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pnauth-limbo").apply { isDaemon = true }
    }
    private var nativeApi: PicoLimboLibrary.NativeApi? = null
    private var cancellationToken: Pointer? = null
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile private var currentStates: LimboServerState = if (settings.enabled) LimboServerState.CREATED else LimboServerState.DISABLED
    @Volatile private var effectiveHost: String = settings.host
    @Volatile private var effectivePort: Int = settings.port
    @Volatile private var controlInstance: LimboControl? = null

    @Synchronized
    override fun start() {
        if (!settings.enabled || isRunning) return
        currentStates = LimboServerState.STARTING
        try {
            Files.createDirectories(dataDir)
            val config = dataDir.resolve("config.toml")
            val configStore = PicoLimboConfigStore()
            configStore.prepareEmbedded(config, settings.host, settings.port)
            val picoConfig = configStore.load(config)
            val endpoint = picoConfig.endpoint()
            if (!isLoopback(endpoint.host)) {
                throw IOException("Embedded PicoLimbo must bind to a loopback address, got " + endpoint.host)
            }
            effectiveHost = endpoint.host
            effectivePort = endpoint.port
            if (effectivePort != settings.port) {
                throw IOException(
                    "Limbo port $effectivePort does not match limbo.port ${settings.port} in pnAuth config"
                )
            }
            val api = PicoLimboLibrary.load(dataDir, settings)
            nativeApi = api
            val token = api.get_cancellation_token()
            cancellationToken = token
            controlInstance = embeddingControl(api)
            val arguments = arrayOf("pico_limbo_java_wrapper", "--config", config.toString())
            isRunning = true
            executor.execute {
                try {
                    api.start_app(token, arguments.size, arguments)
                } finally {
                    synchronized(nativeLock) {
                        isRunning = false
                        currentStates = LimboServerState.STOPPED
                        if (cancellationToken != null) api.cleanup_token(token)
                        cancellationToken = null
                    }
                }
            }
            waitUntilReady()
            currentStates = LimboServerState.RUNNING
        } catch (exception: Exception) {
            isRunning = false
            currentStates = LimboServerState.FAILED
            throw exception
        }
    }

    @Synchronized
    override fun stop() {
        synchronized(nativeLock) {
            val token = cancellationToken
            val api = nativeApi
            if (api != null && token != null) api.stop_app(token)
            controlInstance = null
            isRunning = false
        }
        if (currentStates != LimboServerState.DISABLED) currentStates = LimboServerState.STOPPED
    }

    override fun id(): String = settings.serverName

    override fun state(): LimboServerState = currentStates

    override fun control(): Optional<LimboControl> = Optional.ofNullable(controlInstance)

    fun enabled(): Boolean = settings.enabled

    fun serverName(): String = settings.serverName

    override fun host(): String = effectiveHost

    override fun port(): Int = effectivePort

    override fun close() {
        stop()
        executor.shutdownNow()
    }

    private fun embeddingControl(api: PicoLimboLibrary.NativeApi): LimboControl? {
        return try {
            val version = api.pico_embedding_api_version()
            if (version >= 1) PicoLimboControl(api, version) else null
        } catch (ignored: UnsatisfiedLinkError) {
            null
        }
    }

    private fun waitUntilReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val address = InetSocketAddress(effectiveHost, effectivePort)
        while (System.nanoTime() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(address, 100)
                    return
                }
            } catch (ignored: IOException) {
                try {
                    Thread.sleep(50)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for PicoLimbo", interrupted)
                }
            }
        }
        throw IOException("PicoLimbo did not become ready on $address")
    }

    private inner class PicoLimboControl(
        private val api: PicoLimboLibrary.NativeApi,
        private val version: Int
    ) : LimboControl {

        override fun apiVersion(): Int = version

        override fun isPlayerConnected(playerId: UUID): Boolean {
            synchronized(nativeLock) {
                return api.pico_player_is_connected(handle(), playerId.toString()) != 0
            }
        }

        override fun addBossBar(
            playerId: UUID, barId: UUID, title: String, progress: Float,
            color: ru.privatenull.pnauth.display.BossBarColor, overlay: ru.privatenull.pnauth.display.BossBarOverlay
        ): LimboControlResult {
            synchronized(nativeLock) {
                return result(
                    api.pico_bossbar_add(
                        handle(), playerId.toString(), barId.toString(),
                        title, progress, color.ordinal, overlay.ordinal
                    )
                )
            }
        }

        override fun updateBossBarProgress(playerId: UUID, barId: UUID, progress: Float): LimboControlResult {
            synchronized(nativeLock) {
                return result(api.pico_bossbar_progress(handle(), playerId.toString(), barId.toString(), progress))
            }
        }

        override fun updateBossBarTitle(playerId: UUID, barId: UUID, title: String): LimboControlResult {
            synchronized(nativeLock) {
                return result(api.pico_bossbar_title(handle(), playerId.toString(), barId.toString(), title))
            }
        }

        override fun removeBossBar(playerId: UUID, barId: UUID): LimboControlResult {
            synchronized(nativeLock) {
                return result(api.pico_bossbar_remove(handle(), playerId.toString(), barId.toString()))
            }
        }

        override fun showDialog(playerId: UUID, dialogJson: String): LimboControlResult {
            synchronized(nativeLock) {
                return result(api.pico_dialog_show_json(handle(), playerId.toString(), dialogJson))
            }
        }

        override fun clearDialog(playerId: UUID): LimboControlResult {
            synchronized(nativeLock) {
                return result(api.pico_dialog_clear(handle(), playerId.toString()))
            }
        }

        override fun pollDialogEvent(): Optional<LimboDialogEvent> {
            synchronized(nativeLock) {
                var buffer = ByteArray(1024)
                var result = api.pico_dialog_poll_event(handle(), buffer, buffer.size.toLong())
                if (result == 0) return Optional.empty()
                if (result < 0) {
                    val req = -result
                    if (req > 65536) throw IllegalStateException("Dialog event exceeds maximum size")
                    buffer = ByteArray(req)
                    result = api.pico_dialog_poll_event(handle(), buffer, buffer.size.toLong())
                }
                if (result < 0) throw IllegalStateException("Could not read PicoLimbo dialog event")
                val json = String(buffer, 0, result, StandardCharsets.UTF_8)
                return try {
                    val event = ObjectMapper().readTree(json)
                    Optional.of(
                        LimboDialogEvent(
                            UUID.fromString(event.path("playerId").asText()),
                            event.path("actionId").asText(),
                            event.path("data").toString()
                        )
                    )
                } catch (exception: Exception) {
                    throw IllegalStateException("Invalid PicoLimbo dialog event", exception)
                }
            }
        }

        private fun handle(): Pointer {
            val handle = cancellationToken
            if (handle == null || !isRunning) throw IllegalStateException("PicoLimbo is not running")
            return handle
        }

        private fun result(code: Int): LimboControlResult {
            return LimboControlResult.fromCode(code)
        }
    }

    companion object {
        private fun isLoopback(host: String): Boolean {
            return try {
                java.net.InetAddress.getByName(host).isLoopbackAddress
            } catch (exception: java.net.UnknownHostException) {
                false
            }
        }
    }
}
