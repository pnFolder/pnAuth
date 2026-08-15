package ru.privatenull.pnauth.config;

import ru.privatenull.pnauth.policy.AccessSettings;
import ru.privatenull.pnauth.config.LimboSettings;
import ru.privatenull.pnauth.security.HashAlgorithm;
import ru.privatenull.pnauth.message.MessageFormat;

import java.io.IOException;
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
        PaperSettings paper,
        MessageFormat messageFormat
) {
    public static final int CURRENT_SCHEMA_VERSION = 4;
    private static final String LEGACY_FORK_DOWNLOAD =
            "https://github.com/pnFolder/PicoLimbo/releases/download/v1.13.2-pn.2%2Bmc26.2/";
    private static final String LEGACY_FORK_SHA256 =
            "701ad39c987e01edc659198d166e91d91a3182b8ae7df3bcc7c8366629089e13";

    public static AuthConfig load(Path file, String fallbackJdbcUrl) throws IOException {
        return new PnAuthConfigManager(file, fallbackJdbcUrl).load();
    }

    static AuthConfig fromYaml(PnAuthYamlConfig yaml, Path configFile, String fallbackJdbcUrl) throws IOException {
        if (yaml.configVersion > CURRENT_SCHEMA_VERSION) {
            throw new IOException("config.yml uses unsupported schema version " + yaml.configVersion
                    + "; this pnAuth build supports up to " + CURRENT_SCHEMA_VERSION);
        }
        PnAuthYamlConfig.Database database = required(yaml.database, PnAuthYamlConfig.Database::new);
        PnAuthYamlConfig.Servers servers = required(yaml.servers, PnAuthYamlConfig.Servers::new);
        PnAuthYamlConfig.Security security = required(yaml.security, PnAuthYamlConfig.Security::new);
        PnAuthYamlConfig.Validation validation = required(yaml.validation, PnAuthYamlConfig.Validation::new);
        PnAuthYamlConfig.Access access = required(yaml.access, PnAuthYamlConfig.Access::new);
        PnAuthYamlConfig.Limits limits = required(yaml.limits, PnAuthYamlConfig.Limits::new);
        PnAuthYamlConfig.Features features = required(yaml.features, PnAuthYamlConfig.Features::new);
        PnAuthYamlConfig.Ui ui = required(yaml.ui, PnAuthYamlConfig.Ui::new);
        PnAuthYamlConfig.Limbo limbo = required(yaml.limbo, PnAuthYamlConfig.Limbo::new);
        PnAuthYamlConfig.Paper paper = required(yaml.paper, PnAuthYamlConfig.Paper::new);
        PnAuthYamlConfig.Paper.Teleport teleport = required(paper.teleport, PnAuthYamlConfig.Paper.Teleport::new);
        PnAuthYamlConfig.Paper.Restrictions restrictions = required(
                paper.restrictions, PnAuthYamlConfig.Paper.Restrictions::new);
        PnAuthYamlConfig.Security.Password password = required(security.password, PnAuthYamlConfig.Security.Password::new);
        PnAuthYamlConfig.Security.Login login = required(security.login, PnAuthYamlConfig.Security.Login::new);
        PnAuthYamlConfig.Security.Hashing hashing = required(security.hashing, PnAuthYamlConfig.Security.Hashing::new);
        PnAuthYamlConfig.Features.Premium premium = required(features.premium, PnAuthYamlConfig.Features.Premium::new);
        PnAuthYamlConfig.Features.Session session = required(features.session, PnAuthYamlConfig.Features.Session::new);
        PnAuthYamlConfig.Features.Totp totp = required(features.totp, PnAuthYamlConfig.Features.Totp::new);
        PnAuthYamlConfig.Features.Captcha captcha = required(features.captcha, PnAuthYamlConfig.Features.Captcha::new);
        PnAuthYamlConfig.Ui.Dialogs dialogs = required(ui.dialogs, PnAuthYamlConfig.Ui.Dialogs::new);
        migrateLegacyPicoLimboSource(limbo);

        AuthSettings defaults = AuthSettings.defaults();
        AuthSettings authSettings = new AuthSettings(
                password.minLength,
                password.maxLength,
                login.maxAttempts,
                Duration.ofSeconds(login.lockoutSeconds),
                hashing.pbkdf2Iterations,
                validation.usernamePattern,
                enumValue(hashing.algorithm, defaults.hashAlgorithm()),
                hashing.bcryptCost,
                hashing.argon2Iterations,
                hashing.argon2MemoryKb,
                hashing.argon2Parallelism
        );
        return new AuthConfig(
                locale(yaml.locale),
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
                        premium.enabled,
                        session.restoreOnSameIp,
                        Duration.ofMinutes(session.lifetimeMinutes),
                        Duration.ofSeconds(session.timeoutSeconds),
                        Duration.ofSeconds(session.reminderSeconds),
                        login.banOnFailedLogin,
                        Duration.ofSeconds(login.banSeconds),
                        limits.maxOnlineAccountsPerIp,
                        limits.maxRegisteredAccountsPerIp,
                        limits.excludedIps == null ? java.util.Set.of() : java.util.Set.copyOf(limits.excludedIps),
                        totp.enabled,
                        totp.maxAttempts,
                        Duration.ofSeconds(totp.lockoutSeconds),
                        Duration.ofSeconds(totp.setupLifetimeSeconds),
                        totp.issuer,
                        totp.recoveryCodes,
                        password.repeatOnRegister,
                        new DialogSettings(
                                dialogs.enabled,
                                dialogs.fallbackToCommands,
                                dialogs.allowPlayerPreference,
                                dialogs.minClientProtocol
                        ),
                        new CaptchaSettings(
                                captcha.enabled,
                                Duration.ofSeconds(captcha.lifetimeSeconds),
                                captcha.maxAttempts
                        ),
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
                new PaperSettings(
                        teleport.enabled, teleport.world, teleport.x, teleport.y, teleport.z,
                        teleport.yaw, teleport.pitch, restrictions.movement, restrictions.chat,
                        restrictions.commands, restrictions.interaction, restrictions.breaking,
                        restrictions.placing, restrictions.inventory
                ),
                messageFormat(yaml.messages)
        );
    }

    private static StorageConfig storage(PnAuthYamlConfig.Database database, Path configFile, String fallbackJdbcUrl)
            throws IOException {
        String type = database.type == null ? "" : database.type.trim().toUpperCase(Locale.ROOT);
        if (type.equals("SQLITE") || type.equals("H2")) {
            if ((database.file == null || database.file.isBlank()) && fallbackJdbcUrl != null && !fallbackJdbcUrl.isBlank()) {
                return new StorageConfig(fallbackJdbcUrl, "", "");
            }
            if (database.file == null || database.file.isBlank()) throw new IOException("database.file is empty");
            Path file = Path.of(database.file);
            if (!file.isAbsolute() && configFile.getParent() != null) file = configFile.getParent().resolve(file);
            return new StorageConfig(
                    "jdbc:" + type.toLowerCase(Locale.ROOT) + (type.equals("H2") ? ":file:" : ":")
                            + file.toAbsolutePath().normalize(), "", ""
            );
        }
        if (type.equals("MYSQL") || type.equals("MARIADB") || type.equals("POSTGRESQL")) {
            PnAuthYamlConfig.Connection connection = type.equals("POSTGRESQL")
                    ? required(database.postgresql, () -> new PnAuthYamlConfig.Connection(5432))
                    : required(database.mysql, () -> new PnAuthYamlConfig.Connection(3306));
            if (connection.host == null || connection.host.isBlank()
                    || connection.database == null || connection.database.isBlank()
                    || connection.port < 1 || connection.port > 65_535) {
                throw new IOException("Invalid " + type.toLowerCase(Locale.ROOT) + " connection settings");
            }
            String scheme = type.equals("MARIADB") ? "mariadb" : type.equals("POSTGRESQL") ? "postgresql" : "mysql";
            String timezone = connection.serverTimezone == null || connection.serverTimezone.isBlank()
                    ? "UTC" : connection.serverTimezone;
            try {
                java.time.ZoneId.of(timezone);
            } catch (RuntimeException exception) {
                throw new IOException("Invalid database server timezone: " + timezone, exception);
            }
            String query = switch (type) {
                // VERIFY_IDENTITY guarantees encryption and certificate/hostname validation.
                case "MYSQL" -> "sslMode=" + (connection.useSsl ? "VERIFY_IDENTITY" : "DISABLED")
                        + "&serverTimezone=" + timezone;
                // MariaDB Connector/J 3.x supports the same secure verification mode.
                case "MARIADB" -> "sslMode=" + (connection.useSsl ? "VERIFY_FULL" : "DISABLE")
                        + "&serverTimezone=" + timezone;
                // pgJDBC uses sslmode, not MySQL's useSSL property.
                case "POSTGRESQL" -> "sslmode=" + (connection.useSsl ? "verify-full" : "disable");
                default -> throw new IllegalStateException("Unexpected database type: " + type);
            };
            return new StorageConfig(
                    "jdbc:" + scheme + "://" + connection.host + ":" + connection.port + "/" + connection.database
                            + "?" + query,
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
        values.forEach((key, value) -> {
            if (key == null || value == null) {
                throw new IllegalArgumentException("servers.forced-hosts must not contain null keys or values");
            }
            result.put(key.toLowerCase(Locale.ROOT), value);
        });
        return result;
    }

    static boolean migrateLegacyPicoLimboSource(PnAuthYamlConfig.Limbo limbo) {
        if (LEGACY_FORK_DOWNLOAD.equals(limbo.downloadBaseUrl)
                || LEGACY_FORK_SHA256.equalsIgnoreCase(limbo.downloadSha256)) {
            limbo.downloadBaseUrl = LimboSettings.OFFICIAL_DOWNLOAD_BASE_URL;
            limbo.downloadSha256 = LimboSettings.OFFICIAL_DOWNLOAD_SHA256;
            return true;
        }
        return false;
    }

    private static <T> T required(T value, java.util.function.Supplier<T> fallback) {
        return value == null ? fallback.get() : value;
    }

    private static <T extends Enum<T>> T enumValue(String value, T fallback) throws IOException {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
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

    private static String locale(String value) throws IOException {
        String normalized = value == null ? "ru" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("ru") || normalized.equals("en")) return normalized;
        throw new IOException("Unsupported locale: " + value + ". Supported locales: ru, en");
    }

    private static final class SetDefaults {
        private static java.util.Set<String> commands() {
            return AccessSettings.defaults().unauthenticatedCommands();
        }
    }

    public record StorageConfig(String url, String username, String password) {
    }
}
