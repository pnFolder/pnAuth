package ru.privatenull.pnauth.message;

import org.yaml.snakeyaml.Yaml;
import ru.privatenull.pnauth.api.AuthStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AuthMessages {
    private final Map<String, Object> values;
    private final MessageFormat format;
    private final MessageRenderer renderer;

    private AuthMessages(Map<String, Object> values, MessageFormat format) {
        this.values = Map.copyOf(values);
        this.format = format;
        this.renderer = MessageRenderers.forFormat(format);
    }

    public static AuthMessages load(String locale) throws IOException {
        return load(locale, MessageFormat.LEGACY);
    }

    public static AuthMessages load(String locale, MessageFormat format) throws IOException {
        return new AuthMessages(MessageCatalog.defaults(locale), format == null ? MessageFormat.LEGACY : format);
    }

    public static AuthMessages load(Path directory, String locale, MessageFormat format) throws IOException {
        MessageFileGenerator.ensureAll(directory);
        Path file = MessageFileGenerator.ensure(directory, locale);
        Object root = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> flattened = new HashMap<>(MessageCatalog.defaults(locale));
        flatten("", root, flattened);
        return new AuthMessages(flattened, format == null ? MessageFormat.LEGACY : format);
    }

    public String text(String key) {
        Object value = values.get(key);
        return renderer.render(value == null ? key : String.valueOf(value));
    }

    public String text(String key, Map<String, String> replacements) {
        Object value = values.get(key);
        return renderer.render(value == null ? key : String.valueOf(value), replacements);
    }

    public List<String> lines(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(item -> renderer.render(String.valueOf(item))).toList();
        }
        return List.of(text(key));
    }

    public String prompt(AuthStatus status) {
        return text("prompt." + status.name().toLowerCase(Locale.ROOT));
    }

    public MessageFormat format() {
        return format;
    }

    private static InputStream resource(String name) {
        return AuthMessages.class.getResourceAsStream(name);
    }

    private static void flatten(String prefix, Object value, Map<String, Object> output) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                flatten(prefix.isEmpty() ? key : prefix + "." + key, entry.getValue(), output);
            }
            return;
        }
        output.put(prefix, value);
    }
}
