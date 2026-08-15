package ru.privatenull.pnauth.dependency;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Downloads a verified, platform-specific PacketEvents plugin on the first proxy start. */
public final class PacketEventsBootstrap {
    private static final String BUNGEE_URL = "https://github.com/retrooper/packetevents/releases/download/"
            + "v2.13.0/packetevents-bungeecord-2.13.0.jar";
    private static final String BUNGEE_SHA = "F37CE9320B2E009E9D3A1E405992DE47B41AC5805A6F83147EFD4B869DB25494";
    private static final String VELOCITY_URL = "https://github.com/retrooper/packetevents/releases/download/"
            + "v2.13.0/packetevents-velocity-2.13.0.jar";
    private static final String VELOCITY_SHA = "E797F84ABC349C137396E511CE4F0D7B85E385727A2E82E2FFB6BED0D2FE5C05";

    private PacketEventsBootstrap() {
    }

    public static Result ensure(
            Platform platform,
            Path dataDirectory,
            Path pluginsDirectory,
            Consumer<String> log
    ) throws Exception {
        Path configFile = dataDirectory.resolve("dependencies.yml");
        if (Files.notExists(configFile)) writeDefaults(configFile);
        Settings settings = load(configFile, platform);
        if (!settings.enabled()) return Result.DISABLED;

        Path target = pluginsDirectory.resolve(settings.fileName()).toAbsolutePath().normalize();
        if (!target.getParent().equals(pluginsDirectory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("PacketEvents file-name must not leave the plugins directory");
        }
        if (Files.exists(target)) {
            verify(target, settings.sha256(), platform.descriptor);
            return Result.AVAILABLE;
        }

        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".pnauth-packetevents-", ".download");
        try {
            log.accept("Downloading PacketEvents for " + platform.configKey + " from " + settings.url());
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(settings.url()))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("PacketEvents download returned HTTP " + response.statusCode());
            }
            verify(temporary, settings.sha256(), platform.descriptor);
            moveAtomically(temporary, target);
            log.accept("Installed verified PacketEvents plugin at " + target);
            return Result.INSTALLED_RESTART_REQUIRED;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Settings load(Path file, Platform platform) throws Exception {
        try (InputStream input = Files.newInputStream(file)) {
            Map<?, ?> root = new Yaml().load(input);
            boolean enabled = booleanValue(root.get("auto-install"), true);
            int timeout = integerValue(root.get("timeout-seconds"), 30);
            Map<?, ?> packetEvents = map(root.get("packet-events"), "packet-events");
            Map<?, ?> selected = map(packetEvents.get(platform.configKey), platform.configKey);
            return new Settings(enabled, string(selected, "url"), string(selected, "sha-256"),
                    string(selected, "file-name"), Math.max(5, timeout));
        }
    }

    private static void writeDefaults(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        String yaml = """
                # pnAuth downloads PacketEvents once, verifies it, then stops the proxy.
                # Start the proxy again (or use a process supervisor) to load the new plugin.
                auto-install: true
                timeout-seconds: 30
                packet-events:
                  bungeecord:
                    url: "%s"
                    sha-256: "%s"
                    file-name: "packetevents-bungeecord-2.13.0.jar"
                  velocity:
                    url: "%s"
                    sha-256: "%s"
                    file-name: "packetevents-velocity-2.13.0.jar"
                """.formatted(BUNGEE_URL, BUNGEE_SHA, VELOCITY_URL, VELOCITY_SHA);
        Files.writeString(file, yaml);
    }

    private static void verify(Path file, String expectedHash, String descriptor) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
        }
        String actual = HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
        if (!MessageDigest.isEqual(actual.getBytes(), expectedHash.toUpperCase(Locale.ROOT).getBytes())) {
            throw new SecurityException("PacketEvents SHA-256 mismatch: expected " + expectedHash + ", got " + actual);
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry(descriptor) == null) {
                throw new SecurityException("Downloaded file is not a " + descriptor + " PacketEvents plugin");
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static Map<?, ?> map(Object value, String key) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Missing dependency configuration section: " + key);
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof String text && !text.isBlank()) return text;
        throw new IllegalArgumentException("Missing dependency configuration value: " + key);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean state ? state : fallback;
    }

    private static int integerValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public enum Platform {
        BUNGEECORD("bungeecord", "plugin.yml"),
        VELOCITY("velocity", "velocity-plugin.json");

        private final String configKey;
        private final String descriptor;

        Platform(String configKey, String descriptor) {
            this.configKey = configKey;
            this.descriptor = descriptor;
        }
    }

    public enum Result { AVAILABLE, INSTALLED_RESTART_REQUIRED, DISABLED }

    private record Settings(boolean enabled, String url, String sha256, String fileName, int timeoutSeconds) {
    }
}
