package ru.privatenull.pnauth.limbo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import ru.privatenull.pnauth.config.LimboSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class PicoLimboConfigStore {
    private static final ObjectMapper TOML = new ObjectMapper(new TomlFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PicoLimboConfig load(Path file) throws IOException {
        if (Files.notExists(file)) {
            throw new IOException("Limbo config.toml is missing: " + file);
        }
        return TOML.readValue(file.toFile(), PicoLimboConfig.class);
    }

    /** Embedded pnAuth limbo is loopback-only and does not consume proxy forwarding data. */
    public void prepareEmbedded(Path file) throws IOException {
        prepareEmbedded(file, null, 0);
    }

    /**
     * Creates and keeps the embedded config's bind endpoint in sync with pnAuth's
     * {@code limbo.host} and {@code limbo.port}. Other PicoLimbo settings remain editable.
     */
    public void prepareEmbedded(Path file, String host, int port) throws IOException {
        boolean synchronizeEndpoint = host != null && !host.isBlank() && port > 0 && port <= 65_535;
        String source = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (source.isEmpty() && !synchronizeEndpoint) {
            throw new IOException("Limbo config.toml is missing an endpoint: " + file);
        }
        if (source.isEmpty() && synchronizeEndpoint) {
            source = "# Managed by pnAuth; additional PicoLimbo settings may be added below.\n"
                    + bindLine(host, port) + "\n";
        } else if (synchronizeEndpoint) {
            source = synchronizeBind(source, host, port);
        }
        List<String> retained = new ArrayList<>();
        boolean inForwardingSection = false;
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.equalsIgnoreCase("[forwarding]")) {
                inForwardingSection = true;
                continue;
            }
            if (inForwardingSection && trimmed.startsWith("[")) {
                inForwardingSection = false;
            }
            if (inForwardingSection) continue;
            if (trimmed.matches("(?i)forwarding\\.(method|secret)\\s*=.*")) continue;
            retained.add(line);
        }
        while (!retained.isEmpty() && retained.get(retained.size() - 1).isBlank()) retained.remove(retained.size() - 1);
        retained.add("");
        retained.add("forwarding.method = 'NONE'");
        retained.add("forwarding.secret = ''");
        retained.add("");
        String updated = String.join(System.lineSeparator(), retained);
        if (!updated.equals(source)) {
            writeAtomically(file, updated);
        }
    }

    private static String synchronizeBind(String source, String host, int port) {
        String bind = bindLine(host, port);
        Pattern rootBind = Pattern.compile("(?m)^\\s*bind\\s*=.*(?:\\R|$)");
        if (rootBind.matcher(source).find()) {
            return rootBind.matcher(source).replaceFirst(java.util.regex.Matcher.quoteReplacement(bind + System.lineSeparator()));
        }
        return bind + System.lineSeparator() + source;
    }

    private static String bindLine(String host, int port) {
        return "bind = \"" + host.replace("\\", "\\\\").replace("\"", "\\\"") + ":" + port + "\"";
    }

    private static void writeAtomically(Path file, String contents) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
