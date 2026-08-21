package ru.privatenull.pnauth.limbo

import com.sun.jna.Pointer
import ru.privatenull.pnauth.config.LimboSettings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PicoLimboServer(
    private val dataDirectory: Path,
    private val limboConfigDirectory: Path,
    private val settings: LimboSettings,
    private val picoConfig: PicoLimboConfig
) : LimboServer {
    private val nativeLock = Any()

    @Volatile
    private var state = LimboServerState.STOPPED

    @Volatile
    private var nativeApi: PicoLimboLibrary.NativeApi? = null

    @Volatile
    private var cancellationToken: Pointer? = null

    @Volatile
    private var workerPool: ExecutorService? = null

    override fun id(): String = ID

    override fun host(): String = picoConfig.endpoint(settings.host, settings.port).host

    override fun port(): Int = picoConfig.endpoint(settings.host, settings.port).port

    override fun state(): LimboServerState = state

    @Synchronized
    override fun start() {
        if (state == LimboServerState.RUNNING || state == LimboServerState.STARTING) return
        state = LimboServerState.STARTING
        try {
            Files.createDirectories(limboConfigDirectory)
            PicoLimboConfigStore().prepareEmbedded(limboConfigDirectory.resolve("pico_limbo.toml"), host(), port())
            val api = PicoLimboLibrary.load(dataDirectory, settings)
            val token = api.get_cancellation_token()
            if (token == Pointer.NULL) {
                throw IllegalStateException("Failed to obtain cancellation token for PicoLimbo")
            }
            val pool = Executors.newSingleThreadExecutor { runnable ->
                val thread = Thread(runnable, "pnAuth-PicoLimbo")
                thread.isDaemon = true
                thread
            }
            val configFile = limboConfigDirectory.resolve("pico_limbo.toml").toAbsolutePath().toString()
            val arguments = arrayOf("pico_limbo", "--config", configFile)

            val startupFuture = CompletableFuture.runAsync({
                api.start_app(token, arguments.size, arguments)
            }, pool)

            startupFuture.whenComplete { _, throwable ->
                if (throwable != null) {
                    synchronized(nativeLock) {
                        state = LimboServerState.FAILED
                    }
                }
            }

            waitForPortReady(InetSocketAddress(host(), port()), 10_000)

            synchronized(nativeLock) {
                nativeApi = api
                cancellationToken = token
                workerPool = pool
                state = LimboServerState.RUNNING
            }
        } catch (exception: Exception) {
            state = LimboServerState.FAILED
            stop()
            throw IllegalStateException("Failed to start PicoLimbo server", exception)
        }
    }

    @Synchronized
    override fun stop() {
        if (state == LimboServerState.STOPPED) return
        state = LimboServerState.STOPPED

        var tokenToClean: Pointer? = null
        var apiToUse: PicoLimboLibrary.NativeApi? = null
        var poolToShutdown: ExecutorService? = null

        synchronized(nativeLock) {
            tokenToClean = cancellationToken
            apiToUse = nativeApi
            poolToShutdown = workerPool
            cancellationToken = null
            nativeApi = null
            workerPool = null
        }

        if (apiToUse != null && tokenToClean != null && tokenToClean != Pointer.NULL) {
            try {
                apiToUse!!.stop_app(tokenToClean!!)
            } catch (ignored: Exception) {
            }
        }

        if (poolToShutdown != null) {
            poolToShutdown!!.shutdown()
            try {
                if (!poolToShutdown!!.awaitTermination(5, TimeUnit.SECONDS)) {
                    poolToShutdown!!.shutdownNow()
                }
            } catch (interrupted: InterruptedException) {
                poolToShutdown!!.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }

        if (apiToUse != null && tokenToClean != null && tokenToClean != Pointer.NULL) {
            try {
                apiToUse!!.cleanup_token(tokenToClean!!)
            } catch (ignored: Exception) {
            }
        }

        state = LimboServerState.STOPPED
    }

    private fun waitForPortReady(address: InetSocketAddress, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(address, 200)
                    return
                }
            } catch (ignored: IOException) {
                try {
                    Thread.sleep(100)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for PicoLimbo", interrupted)
                }
            }
        }
        throw IOException("PicoLimbo did not become ready on $address")
    }

    companion object {
        const val ID: String = "pico_limbo"
    }
}
