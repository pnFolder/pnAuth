package ru.privatenull.pnauth.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The only entry point for the persistent pnAuth configuration.
 *
 * <p>The YAML schema lives in {@link PnAuthYamlConfig}; Elytrium Serializer
 * creates the file with comments and defaults. {@link AuthConfig} is the
 * immutable, validated runtime representation used by the plugin.</p>
 */
public final class PnAuthConfigManager {
    private static final List<String> REQUIRED_SCHEMA_KEYS = List.of(
            "config-version:",
            "setup-lifetime-seconds:",
            "restore-on-same-ip:",
            "paper:"
    );
    private final Path file;
    private final String fallbackJdbcUrl;

    public PnAuthConfigManager(Path file, String fallbackJdbcUrl) {
        this.file = file.toAbsolutePath().normalize();
        this.fallbackJdbcUrl = fallbackJdbcUrl == null ? "" : fallbackJdbcUrl;
    }

    /** Loads and validates config.yml, creating a documented default on first start. */
    public AuthConfig load() throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);

        boolean created = Files.notExists(file);
        boolean schemaComplete = !created && hasRequiredSchemaKeys(file);
        byte[] original = created ? null : Files.readAllBytes(file);
        PnAuthYamlConfig yaml = new PnAuthYamlConfig(file);
        try {
            if (created) yaml.save();
            else yaml.reload();
            boolean legacyLimboSource = yaml.limbo != null && AuthConfig.migrateLegacyPicoLimboSource(yaml.limbo);
            AuthConfig config = AuthConfig.fromYaml(yaml, file, fallbackJdbcUrl);
            boolean needsSchemaWrite = !schemaComplete || yaml.configVersion < AuthConfig.CURRENT_SCHEMA_VERSION
                    || legacyLimboSource;
            // Existing administrators keep their layout and comments. We rewrite only
            // first-run files and schema upgrades to add documented defaults once. A
            // recoverable backup is always made before the serializer replaces a file.
            if (needsSchemaWrite && !created) {
                backupBeforeMigration(original);
                yaml.configVersion = AuthConfig.CURRENT_SCHEMA_VERSION;
                yaml.save();
            }
            return config;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid pnAuth configuration at " + file + ": " + exception.getMessage(), exception);
        }
    }

    private static boolean hasRequiredSchemaKeys(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file).stream()
                .map(String::stripLeading)
                .toList();
        return REQUIRED_SCHEMA_KEYS.stream().allMatch(key -> lines.stream().anyMatch(line -> line.startsWith(key)));
    }

    private void backupBeforeMigration(byte[] original) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".bak.tmp");
        try {
            Files.write(temporary, original);
            try {
                Files.move(temporary, backup, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
