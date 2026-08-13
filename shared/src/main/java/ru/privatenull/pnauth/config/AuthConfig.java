package ru.privatenull.pnauth.config;

import ru.privatenull.pnauth.policy.AccessSettings;
import ru.privatenull.pnauth.config.LimboSettings;
import ru.privatenull.pnauth.security.HashAlgorithm;
import ru.privatenull.pnauth.message.MessageFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public record AuthConfig(
        String locale,
        StorageConfig storage,
        AuthSettings security,
        ProxySettings proxy,
        AccessSettings access,
        FeatureSettings features,
        LimboSettings limbo,
        MessageFormat messageFormat
) {
    public static AuthConfig load(Path file, String fallbackJdbcUrl) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        PnAuthYamlConfig yaml = new PnAuthYamlConfig(file);
        try {
            yaml.reload();
        } catch (RuntimeException exception) {
            throw new IOException("Could not load config.yml", exception);
        }
        return from(yaml, file, fallbackJdbcUrl);
    }

    private static AuthConfig from(PnAuthYamlConfig yaml, Path configFile, String fallbackJdbcUrl) throws IOException {
        PnAuthYamlConfig.Database database = required(yaml.database, PnAuthYamlConfig.Database::new);
        PnAuthYamlConfig.Servers servers = required(yaml.servers, PnAuthYamlConfig.Servers::new);
        PnAuthYamlConfig.Security security = required(yaml.security, PnAuthYamlConfig.Security::new);
        PnAuthYamlConfig.Validation validation = required(yaml.validation, PnAuthYamlConfig.Validation::new);
        PnAuthYamlConfig.Access access = required(yaml.access, PnAuthYamlConfig.Access::new);
        PnAuthYamlConfig.Limits limits = required(yaml.limits, PnAuthYamlConfig.Limits::new);
        PnAuthYamlConfig.Features features = required(yaml.features, PnAuthYamlConfig.Features::new);
        PnAuthYamlConfig.Ui ui = required(yaml.ui, PnAuthYamlConfig.Ui::new);
        PnAuthYamlConfig.Limbo limbo = required(yaml.limbo, PnAuthYamlConfig.Limbo::new);

        AuthSettings defaults = AuthSettings.defaults();
        AuthSettings authSettings = new AuthSettings(
                security.password.minLength,
                security.password.maxLength,
                security.login.maxAttempts,
                Duration.ofSeconds(security.login.lockoutSeconds),
                security.hashing.pbkdf2Iterations,
                validation.usernamePattern,
                enumValue(security.hashing.algorithm, defaults.hashAlgorithm()),
                security.hashing.bcryptCost,
                security.hashing.argon2Iterations,
                security.hashing.argon2MemoryKb,
                security.hashing.argon2Parallelism
        );
        return new AuthConfig(
                yaml.locale,
                storage(database, configFile, fallbackJdbcUrl),
                authSettings,
                new ProxySettings(
                        servers.requireAuthBeforeServer,
                        servers.authServer,
                        servers.backendServer,
                        lowerCaseKeys(servers.forcedHosts)
                ),
                new AccessSettings(access.blockChat, access.unauthenticatedCommands == null
                        ? SetDefaults.commands() : java.util.Set.copyOf(access.unauthenticatedCommands)),
                new FeatureSettings(
                        features.premium.enabled,
                        Duration.ofMinutes(features.session.lifetimeMinutes),
                        Duration.ofSeconds(features.session.timeoutSeconds),
                        Duration.ofSeconds(features.session.reminderSeconds),
                        security.login.banOnFailedLogin,
                        Duration.ofSeconds(security.login.banSeconds),
                        limits.maxOnlineAccountsPerIp,
                        limits.maxRegisteredAccountsPerIp,
                        limits.excludedIps == null ? java.util.Set.of() : java.util.Set.copyOf(limits.excludedIps),
                        features.totp.enabled,
                        features.totp.maxAttempts,
                        Duration.ofSeconds(features.totp.lockoutSeconds),
                        features.totp.issuer,
                        features.totp.recoveryCodes,
                        security.password.repeatOnRegister,
                        new DialogSettings(
                                ui.dialogs.enabled,
                                ui.dialogs.fallbackToCommands,
                                ui.dialogs.allowPlayerPreference,
                                ui.dialogs.minClientProtocol
                        ),
                        ui.bossbar,
                        ui.title,
                        ui.actionbar
                ),
                new LimboSettings(
                        limbo.provider,
                        limbo.enabled,
                        limbo.serverName,
                        limbo.host,
                        limbo.port,
                        limbo.autoDownload,
                        limbo.downloadBaseUrl,
                        limbo.downloadSha256
                ),
                messageFormat(yaml.messages)
        );
    }

    private static StorageConfig storage(PnAuthYamlConfig.Database database, Path configFile, String fallbackJdbcUrl)
            throws IOException {
        String type = database.type.toUpperCase(Locale.ROOT);
        if (type.equals("SQLITE") || type.equals("H2")) {
            Path file = Path.of(database.file);
            if (!file.isAbsolute() && configFile.getParent() != null) file = configFile.getParent().resolve(file);
            return new StorageConfig(
                    "jdbc:" + type.toLowerCase(Locale.ROOT) + (type.equals("H2") ? ":file:" : ":")
                            + file.toAbsolutePath().normalize(), "", ""
            );
        }
        if (type.equals("MYSQL") || type.equals("MARIADB") || type.equals("POSTGRESQL")) {
            PnAuthYamlConfig.Connection connection = type.equals("POSTGRESQL")
                    ? database.postgresql : database.mysql;
            String scheme = type.equals("MARIADB") ? "mariadb" : type.equals("POSTGRESQL") ? "postgresql" : "mysql";
            return new StorageConfig(
                    "jdbc:" + scheme + "://" + connection.host + ":" + connection.port + "/" + connection.database
                            + "?useSSL=" + connection.useSsl + "&serverTimezone=" + connection.serverTimezone,
                    connection.username, connection.password
            );
        }
        if (type.equals("JDBC")) {
            if (database.url == null || database.url.isBlank()) throw new IOException("database.url is empty");
            return new StorageConfig(database.url, database.username, database.password);
        }
        throw new IOException("Unknown database type: " + database.type);
    }

    private static Map<String, String> lowerCaseKeys(Map<String, String> values) {
        if (values == null) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private static <T> T required(T value, java.util.function.Supplier<T> fallback) {
        return value == null ? fallback.get() : value;
    }

    private static <T extends Enum<T>> T enumValue(String value, T fallback) throws IOException {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid enum value: " + value, exception);
        }
    }

    private static MessageFormat messageFormat(PnAuthYamlConfig.Messages messages) throws IOException {
        try {
            return MessageFormat.parse(messages == null ? null : messages.format);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid messages.format", exception);
        }
    }

    private static final class SetDefaults {
        private static java.util.Set<String> commands() {
            return AccessSettings.defaults().unauthenticatedCommands();
        }
    }

    public record StorageConfig(String url, String username, String password) {
    }
}
