package ru.privatenull.pnauth.message;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MessageFileGenerator {
    private MessageFileGenerator() {
    }

    public static void ensureAll(Path directory) throws IOException {
        Files.createDirectories(directory);
        ensure(directory, "ru");
        ensure(directory, "en");
    }

    public static Path ensure(Path directory, String locale) throws IOException {
        Files.createDirectories(directory);
        String normalized = normalize(locale);
        Path file = directory.resolve("messages_" + normalized + ".yml");
        if (Files.notExists(file)) {
            write(file, MessageCatalog.defaults(normalized));
        } else {
            // Parse once to report malformed files at startup, but never re-serialize a
            // file owned by an administrator. Formatting, comments and unknown keys are
            // part of that file's contract; missing future keys use built-in fallbacks.
            read(file);
        }
        return file;
    }

    private static Map<String, Object> read(Path file) throws IOException {
        Object root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> flattened = new LinkedHashMap<>();
        if (root == null) return flattened;
        if (!(root instanceof Map<?, ?>)) {
            throw new IOException("Message file root must be a YAML mapping: " + file);
        }
        flatten("", root, flattened);
        return flattened;
    }

    private static void write(Path file, Map<String, Object> values) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        String header = "# pnAuth messages are generated from code defaults.\n"
                + "# Edit values below. Existing values are preserved when the plugin is updated.\n"
                + "# Missing keys use built-in defaults without rewriting this file.\n\n";
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, header + new Yaml(options).dump(unflatten(values)), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Object> unflatten(Map<String, Object> values) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String[] path = entry.getKey().split("\\.");
            Map<String, Object> current = root;
            for (int i = 0; i < path.length - 1; i++) {
                Object child = current.get(path[i]);
                if (!(child instanceof Map<?, ?>)) {
                    child = new LinkedHashMap<String, Object>();
                    current.put(path[i], child);
                }
                current = cast(child);
            }
            current.put(path[path.length - 1], entry.getValue());
        }
        return root;
    }

    private static void flatten(String prefix, Object value, Map<String, Object> output) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                flatten(prefix.isEmpty() ? key : prefix + "." + key, entry.getValue(), output);
            }
            return;
        }
        if (value != null) output.put(prefix, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static String normalize(String locale) {
        String value = locale == null ? "ru" : locale.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z]{2}") ? value : "ru";
    }
}
