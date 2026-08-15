package ru.privatenull.pnauth.limbo

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import ru.privatenull.pnauth.config.LimboSettings
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

internal object PicoLimboLibrary {
    private const val DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000
    private const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024

    @Throws(Exception::class)
    fun load(dataDirectory: Path, settings: LimboSettings): NativeApi {
        val wrapperName = "pico_limbo_java_wrapper.jar"
        val wrapper = dataDirectory.resolve(wrapperName)
        Files.createDirectories(dataDirectory)
        val wrapperChanged = Files.notExists(wrapper) || settings.autoDownload &&
                !sha256(wrapper).equals(settings.downloadSha256, ignoreCase = true)
        if (wrapperChanged) {
            downloadAndVerify(wrapper, settings.downloadBaseUrl + wrapperName, settings.downloadSha256)
        }
        val nativeLibrary = dataDirectory.resolve(nativeFileName())
        if (wrapperChanged || Files.notExists(nativeLibrary)) {
            extractNativeLibrary(wrapper, nativeLibrary)
        }
        return Native.load(
            nativeLibrary.toAbsolutePath().toString(), NativeApi::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to StandardCharsets.UTF_8.name())
        )
    }

    private fun downloadAndVerify(target: Path, url: String, expected: String) {
        val temporary = target.resolveSibling(target.fileName.toString() + ".download")
        try {
            val connection = URI.create(url).toURL().openConnection()
            connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MILLIS
            if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                throw IOException("PicoLimbo wrapper exceeds maximum download size")
            }
            connection.getInputStream().use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        downloaded += read.toLong()
                        if (downloaded > MAX_DOWNLOAD_BYTES) {
                            throw IOException("PicoLimbo wrapper exceeds maximum download size")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actual = sha256(temporary)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw SecurityException("PicoLimbo checksum mismatch")
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (ignored: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun extractNativeLibrary(wrapper: Path, target: Path) {
        val entryName = nativeResourcePath()
        val temporary = target.resolveSibling(target.fileName.toString() + ".extract")
        try {
            ZipFile(wrapper.toFile()).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: throw IllegalStateException("PicoLimbo wrapper does not contain $entryName")
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (ignored: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        val result = StringBuilder()
        for (value in digest.digest()) result.append(String.format("%02x", value))
        return result.toString()
    }

    private fun nativeFileName(): String {
        var architecture = System.getProperty("os.arch").lowercase()
        if (architecture == "amd64" || architecture == "x86_64") architecture = "x86_64"
        if (Platform.isWindows() && architecture == "x86_64") return "pico_limbo_lib.dll"
        if (Platform.isLinux() && (architecture == "x86_64" || architecture == "aarch64")) {
            return "libpico_limbo_lib.so"
        }
        if (Platform.isMac() && architecture == "aarch64") return "libpico_limbo_lib.dylib"
        throw UnsupportedOperationException(
            "Unsupported PicoLimbo platform: " + System.getProperty("os.name") + "/" + architecture
        )
    }

    private fun nativeResourcePath(): String {
        var architecture = System.getProperty("os.arch").lowercase()
        if (architecture == "amd64" || architecture == "x86_64") architecture = "x86_64"
        if (Platform.isWindows() && architecture == "x86_64") return "windows/x86_64/pico_limbo_lib.dll"
        if (Platform.isLinux() && (architecture == "x86_64" || architecture == "aarch64")) {
            return "linux/$architecture/libpico_limbo_lib.so"
        }
        if (Platform.isMac() && architecture == "aarch64") {
            return "macos/aarch64/libpico_limbo_lib.dylib"
        }
        throw UnsupportedOperationException(
            "Unsupported PicoLimbo platform: " + System.getProperty("os.name") + "/" + architecture
        )
    }

    interface NativeApi : Library {
        fun start_app(token: Pointer, argc: Int, argv: Array<String>)

        fun stop_app(token: Pointer)

        fun get_cancellation_token(): Pointer

        fun cleanup_token(token: Pointer)
    }
}
