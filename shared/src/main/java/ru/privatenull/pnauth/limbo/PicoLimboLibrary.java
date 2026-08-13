package ru.privatenull.pnauth.limbo;

import ru.privatenull.pnauth.config.LimboSettings;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PicoLimboLibrary {
    private PicoLimboLibrary() {
    }

    static NativeApi load(Path dataDirectory, LimboSettings settings) throws Exception {
        String wrapperName = "pico_limbo_java_wrapper.jar";
        Path wrapper = dataDirectory.resolve(wrapperName);
        Files.createDirectories(dataDirectory);
        boolean wrapperChanged = Files.notExists(wrapper) || settings.autoDownload()
                && !sha256(wrapper).equalsIgnoreCase(settings.downloadSha256());
        if (wrapperChanged) {
            downloadAndVerify(wrapper, settings.downloadBaseUrl() + wrapperName, settings.downloadSha256());
        }
        Path nativeLibrary = dataDirectory.resolve(nativeFileName());
        if (wrapperChanged || Files.notExists(nativeLibrary)) {
            extractNativeLibrary(wrapper, nativeLibrary);
        }
        return Native.load(nativeLibrary.toAbsolutePath().toString(), NativeApi.class);
    }

    private static void downloadAndVerify(Path target, String url, String expected) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".download");
        try (InputStream input = URI.create(url).toURL().openStream()) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        String actual = sha256(temporary);
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(temporary);
            throw new SecurityException("PicoLimbo checksum mismatch");
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void extractNativeLibrary(Path wrapper, Path target) throws Exception {
        String entryName = nativeResourcePath();
        Path temporary = target.resolveSibling(target.getFileName() + ".extract");
        try (ZipFile zip = new ZipFile(wrapper.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("PicoLimbo wrapper does not contain " + entryName);
            }
            try (InputStream input = zip.getInputStream(entry)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static String nativeFileName() {
        String architecture = System.getProperty("os.arch").toLowerCase();
        if (architecture.equals("amd64") || architecture.equals("x86_64")) architecture = "x86_64";
        if (Platform.isWindows() && architecture.equals("x86_64")) return "pico_limbo_lib.dll";
        if (Platform.isLinux() && (architecture.equals("x86_64") || architecture.equals("aarch64"))) {
            return "libpico_limbo_lib.so";
        }
        if (Platform.isMac() && architecture.equals("aarch64")) return "libpico_limbo_lib.dylib";
        throw new UnsupportedOperationException("Unsupported PicoLimbo platform: "
                + System.getProperty("os.name") + "/" + architecture);
    }

    private static String nativeResourcePath() {
        String architecture = System.getProperty("os.arch").toLowerCase();
        if (architecture.equals("amd64") || architecture.equals("x86_64")) architecture = "x86_64";
        if (Platform.isWindows() && architecture.equals("x86_64")) return "windows/x86_64/pico_limbo_lib.dll";
        if (Platform.isLinux() && (architecture.equals("x86_64") || architecture.equals("aarch64"))) {
            return "linux/" + architecture + "/libpico_limbo_lib.so";
        }
        if (Platform.isMac() && architecture.equals("aarch64")) {
            return "macos/aarch64/libpico_limbo_lib.dylib";
        }
        throw new UnsupportedOperationException("Unsupported PicoLimbo platform: "
                + System.getProperty("os.name") + "/" + architecture);
    }

    interface NativeApi extends Library {
        void start_app(Pointer token, int argc, String[] argv);

        void stop_app(Pointer token);

        Pointer get_cancellation_token();

        void cleanup_token(Pointer token);
    }
}
