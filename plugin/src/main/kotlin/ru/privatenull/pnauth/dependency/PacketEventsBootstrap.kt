package ru.privatenull.pnauth.dependency

import ru.privatenull.pnauth.configuration.SafeYaml
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Locale
import java.util.function.Consumer
import java.util.zip.ZipFile

/** Downloads a verified, platform-specific PacketEvents plugin on the first proxy start. */
object PacketEventsBootstrap {
    private const val BUNGEE_URL = "https://github.com/retrooper/packetevents/releases/download/" +
            "v2.13.0/packetevents-bungeecord-2.13.0.jar"
    private const val BUNGEE_SHA = "F37CE9320B2E009E9D3A1E405992DE47B41AC5805A6F83147EFD4B869DB25494"
    private const val VELOCITY_URL = "https://github.com/retrooper/packetevents/releases/download/" +
            "v2.13.0/packetevents-velocity-2.13.0.jar"
    private const val VELOCITY_SHA = "E797F84ABC349C137396E511CE4F0D7B85E385727A2E82E2FFB6BED0D2FE5C05"

    @JvmStatic
    @Throws(Exception::class)
    fun ensure(
        platform: Platform,
        dataDirectory: Path,
        pluginsDirectory: Path,
        log: Consumer<String>
    ): Result {
        val configFile = dataDirectory.resolve("dependencies.yml")
        if (Files.notExists(configFile)) writeDefaults(configFile)
        val settings = load(configFile, platform)
        if (!settings.enabled) return Result.DISABLED

        val target = pluginsDirectory.resolve(settings.fileName).toAbsolutePath().normalize()
        if (target.parent != pluginsDirectory.toAbsolutePath().normalize()) {
            throw IllegalArgumentException("PacketEvents file-name must not leave the plugins directory")
        }
        if (Files.exists(target)) {
            verify(target, settings.sha256, platform.descriptor)
            return Result.AVAILABLE
        }

        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".pnauth-packetevents-", ".download")
        try {
            val downloadUri = validateDownloadUri(settings.url, log)
            log.accept("Downloading PacketEvents for ${platform.configKey} from $downloadUri")
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds.toLong()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(downloadUri)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds.toLong()))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary))
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("PacketEvents download returned HTTP ${response.statusCode()}")
            }
            verify(temporary, settings.sha256, platform.descriptor)
            moveAtomically(temporary, target)
            log.accept("Installed verified PacketEvents plugin at $target")
            return Result.INSTALLED_RESTART_REQUIRED
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun load(file: Path, platform: Platform): Settings {
        Files.newInputStream(file).use { input ->
            val root = SafeYaml.create().load<Map<*, *>>(input) ?: emptyMap<Any, Any>()
            val enabled = booleanValue(root["auto-install"], true)
            val timeout = integerValue(root["timeout-seconds"], 30)
            val packetEvents = map(root["packet-events"], "packet-events")
            val selected = map(packetEvents[platform.configKey], platform.configKey)
            return Settings(
                enabled = enabled,
                url = string(selected, "url"),
                sha256 = string(selected, "sha-256"),
                fileName = string(selected, "file-name"),
                timeoutSeconds = timeout.coerceAtLeast(5)
            )
        }
    }

    private fun validateDownloadUri(url: String, log: Consumer<String>): URI {
        val uri = try {
            URI.create(url)
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("Invalid PacketEvents URL: $url", exception)
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("PacketEvents URL must be https:// and contain a host")
        }
        val host = uri.host.lowercase(Locale.ROOT)
        val trusted = host == "github.com" || host.endsWith(".github.com") ||
            host == "objects.githubusercontent.com" || host.endsWith(".githubusercontent.com")
        if (!trusted) {
            log.accept("WARNING: PacketEvents download host is not a GitHub domain: $host")
        }
        return uri
    }

    private fun writeDefaults(file: Path) {
        Files.createDirectories(file.parent)
        val yaml = """
            # pnAuth downloads PacketEvents once, verifies it, then stops the proxy.
            # Start the proxy again (or use a process supervisor) to load the new plugin.
            auto-install: true
            timeout-seconds: 30
            packet-events:
              bungeecord:
                url: "$BUNGEE_URL"
                sha-256: "$BUNGEE_SHA"
                file-name: "packetevents-bungeecord-2.13.0.jar"
              velocity:
                url: "$VELOCITY_URL"
                sha-256: "$VELOCITY_SHA"
                file-name: "packetevents-velocity-2.13.0.jar"
            """.trimIndent()
        Files.writeString(file, yaml)
    }

    private fun verify(file: Path, expectedHash: String, descriptor: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = HexFormat.of().formatHex(digest.digest()).uppercase(Locale.ROOT)
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedHash.uppercase(Locale.ROOT).toByteArray())) {
            throw SecurityException("PacketEvents SHA-256 mismatch: expected $expectedHash, got $actual")
        }
        ZipFile(file.toFile()).use { zip ->
            if (zip.getEntry(descriptor) == null) {
                throw SecurityException("Downloaded file is not a $descriptor PacketEvents plugin")
            }
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (ignored: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun map(value: Any?, key: String): Map<*, *> {
        if (value is Map<*, *>) return value
        throw IllegalArgumentException("Missing dependency configuration section: $key")
    }

    private fun string(map: Map<*, *>, key: String): String {
        val value = map[key]
        if (value is String && value.isNotBlank()) return value
        throw IllegalArgumentException("Missing dependency configuration value: $key")
    }

    private fun booleanValue(value: Any?, fallback: Boolean): Boolean {
        return if (value is Boolean) value else fallback
    }

    private fun integerValue(value: Any?, fallback: Int): Int {
        return if (value is Number) value.toInt() else fallback
    }

    enum class Platform(val configKey: String, val descriptor: String) {
        BUNGEECORD("bungeecord", "plugin.yml"),
        VELOCITY("velocity", "velocity-plugin.json")
    }

    enum class Result { AVAILABLE, INSTALLED_RESTART_REQUIRED, DISABLED }

    /**
     * Prints a standard first-run notice when PacketEvents was installed automatically.
     *
     * This message is intentionally identical across proxy platforms except for the platform name,
     * so runtimes do not have to duplicate the same logging boilerplate.
     */
    @JvmStatic
    fun logRestartNotice(platform: Platform, warn: Consumer<String>) {
        val proxyName = when (platform) {
            Platform.BUNGEECORD -> "BungeeCord"
            Platform.VELOCITY -> "Velocity"
        }
        warn.accept("============================================================")
        warn.accept(" pnAuth FIRST-RUN SETUP")
        warn.accept(" PacketEvents was downloaded and SHA-256 verified successfully.")
        warn.accept(" The proxy is stopping intentionally so $proxyName can load it.")
        warn.accept(" START THE PROXY ONE MORE TIME to finish enabling pnAuth.")
        warn.accept(" Automatic process restart requires an external server wrapper.")
        warn.accept(" Settings: plugins/pnAuth/dependencies.yml")
        warn.accept("============================================================")
    }

    private data class Settings(
        val enabled: Boolean,
        val url: String,
        val sha256: String,
        val fileName: String,
        val timeoutSeconds: Int
    )
}
