package ru.privatenull.pnauth.message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Code-first message defaults used to generate editable locale files. */
public final class MessageCatalog {
    private MessageCatalog() {
    }

    public static Map<String, Object> defaults(String locale) {
        return "ru".equals(normalize(locale)) ? russian() : english();
    }

    private static Map<String, Object> russian() {
        Map<String, Object> values = base(
                "Эта команда доступна только игроку.", "Не удалось выполнить операцию авторизации.",
                "Использование: /register <пароль> <повтор>", "Использование: /login <пароль>",
                "Использование: /logout", "Использование: /changepassword <старый> <новый>",
                "Использование: /status", "Использование: /totp <enable|verify|disable> [код]",
                "Использование: /unregister <пароль>", "Использование: /premium",
                "Использование: /auth ui <auto|on|off>");
        putCommon(values,
                "Аккаунт не зарегистрирован. Используйте /register <пароль> <повтор>.",
                "Введите пароль командой /login <пароль>.", "Вы авторизованы.",
                "Профиль еще загружается, повторите команду через секунду.",
                "Введите код 2FA командой /totp verify <код>.", "Готово.",
                "Этот аккаунт уже зарегистрирован.", "Это имя уже занято.", "Недопустимое имя игрока.",
                "Аккаунт не зарегистрирован.", "Вы уже авторизованы.", "Сначала войдите в аккаунт.",
                "Профиль еще не загружен, повторите команду через секунду.", "Неверный пароль.",
                "Пароли не совпадают.", "Пароль имеет недопустимую длину.", "Слишком много попыток входа.",
                "Вход временно заблокирован. Попробуйте позже.", "Введите код двухфакторной аутентификации: /totp verify <код>.",
                "Неверный код двухфакторной аутентификации.", "Двухфакторная аутентификация уже включена.",
                "Двухфакторная аутентификация не включена.", "Двухфакторная аутентификация включена.",
                "Двухфакторная аутентификация отключена.", "Сначала начните настройку 2FA командой /totp enable.",
                "Неверный код восстановления.", "Режим dialog UI изменен.",
                "Игрокам запрещено менять режим dialog UI.", "Статус: авторизован.", "Статус: требуется вход.",
                "Статус: требуется регистрация.", "Статус: профиль загружается.", "Сначала авторизуйтесь.",
                "&cСервер авторизации не найден: {server}", "&cНе удалось проверить подключение к базе данных.",
                "С этого IP уже подключено слишком много аккаунтов.", "С этого IP зарегистрировано слишком много аккаунтов.",
                "Основной сервер авторизации недоступен.", "Это подключение временно заблокировано.",
                "Недостаточно прав.", "Игрок не найден.");
        putAdmin(values, "Использование: /auth <unregister|changepassword|forcelogin|forceregister|forcepremium|migrate>",
                "Использование: /auth unregister <игрок>", "Использование: /auth changepassword <игрок> <пароль>",
                "Использование: /auth forcelogin <игрок>", "Использование: /auth forceregister <игрок> <пароль>",
                "Использование: /auth forcepremium <игрок>",
                "Использование: /auth migrate <TIAUTH|AUTHME|MCAUTH|LIMBOAUTH|NLOGIN> <JDBC URL> [user] [password]",
                "Аккаунт игрока {player} удален.", "Пароль игрока {player} изменен.",
                "Игрок {player} принудительно авторизован.", "Аккаунт игрока {player} зарегистрирован.",
                "Premium-режим игрока {player} изменен.", "Импортировано аккаунтов: {count}.",
                "Не удалось выполнить миграцию базы данных.");
        putUi(values, "Вы вышли из аккаунта.", "Аккаунт удален.", "Секрет 2FA: {secret}",
                 "Ссылка для приложения: {uri}", "Коды восстановления: {codes}",
                 "Подтвердите настройку: /totp verify <код>", "Регистрация", "Создайте пароль для этого аккаунта.", "Пароль", "Повторите пароль",
                 "Зарегистрироваться", "Авторизация", "Введите пароль, чтобы продолжить.", "Пароль", "Авторизоваться", "Авторизуйтесь командой /login <пароль>.",
                "Зарегистрируйтесь командой /register <пароль> <повтор>.", "Вы не успели авторизоваться вовремя.",
                "Авторизация", "Выполните команду входа", "Авторизуйтесь, чтобы продолжить", "Ожидание авторизации");
        return values;
    }

    private static Map<String, Object> english() {
        Map<String, Object> values = base(
                "This command is available only to players.", "The authentication operation could not be completed.",
                "Usage: /register <password> <confirmation>", "Usage: /login <password>", "Usage: /logout",
                "Usage: /changepassword <old> <new>", "Usage: /status", "Usage: /totp <enable|verify|disable> [code]",
                "Usage: /unregister <password>", "Usage: /premium", "Usage: /auth ui <auto|on|off>");
        putCommon(values,
                "Account is not registered. Use /register <password> <confirmation>.",
                "Enter your password with /login <password>.", "You are authenticated.",
                "Your profile is still loading, try again in a second.", "Enter your 2FA code with /totp verify <code>.",
                "Done.", "This account is already registered.", "This username is already taken.", "The username is invalid.",
                "The account is not registered.", "You are already authenticated.", "Log in first.",
                "Your profile is not loaded yet, try again in a second.", "Invalid password.", "Passwords do not match.",
                "The password length is invalid.", "Too many login attempts.", "Login is temporarily locked. Try again later.",
                "Enter your two-factor code: /totp verify <code>.", "Invalid two-factor code.",
                "Two-factor authentication is already enabled.", "Two-factor authentication is not enabled.",
                "Two-factor authentication enabled.", "Two-factor authentication disabled.",
                "Start setup with /totp enable first.", "Invalid recovery code.", "Dialog UI preference updated.",
                "Players cannot change the dialog UI preference.", "Status: authenticated.", "Status: login required.",
                "Status: registration required.", "Status: profile is loading.", "Authenticate first.",
                "&cAuthentication server was not found: {server}", "&cThe database could not verify this connection.",
                "Too many accounts are already connected from this IP.", "Too many accounts are registered from this IP.",
                "The backend server is unavailable.", "This connection is temporarily blocked.", "You do not have permission.",
                "Player not found.");
        putAdmin(values, "Usage: /auth <unregister|changepassword|forcelogin|forceregister|forcepremium|migrate>",
                "Usage: /auth unregister <player>", "Usage: /auth changepassword <player> <password>",
                "Usage: /auth forcelogin <player>", "Usage: /auth forceregister <player> <password>",
                "Usage: /auth forcepremium <player>",
                "Usage: /auth migrate <TIAUTH|AUTHME|MCAUTH|LIMBOAUTH|NLOGIN> <JDBC URL> [user] [password]",
                "Account {player} was deleted.", "Password for {player} was changed.",
                "Player {player} was force-authenticated.", "Account for {player} was registered.",
                "Premium mode for {player} was changed.", "Imported accounts: {count}.", "Database migration failed.");
        putUi(values, "You have logged out.", "Your account was deleted.", "2FA secret: {secret}",
                  "Authenticator URI: {uri}", "Recovery codes: {codes}", "Confirm setup with /totp verify <code>",
                 "Registration", "Create a password for this account.", "Password", "Repeat password", "Register", "Login", "Enter your password to continue.", "Password", "Log in",
                "Log in with /login <password>.", "Register with /register <password> <confirmation>.",
                "You did not authenticate in time.", "Authentication", "Enter your login command",
                "Authenticate to continue", "Waiting for authentication");
        return values;
    }

    private static Map<String, Object> base(String... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] keys = {"only-player", "operation-error", "usage.register", "usage.login", "usage.logout",
                "usage.changepassword", "usage.status", "usage.totp", "usage.unregister", "usage.premium", "usage.ui"};
        for (int i = 0; i < keys.length; i++) result.put(keys[i], values[i]);
        result.put("help", List.of("&eAuthentication commands:", "&f/register <password> <confirmation>",
                "&f/login <password>", "&f/logout", "&f/changepassword <old> <new>", "&f/totp <enable|verify|disable> [code]", "&f/status"));
        return result;
    }

    private static void putCommon(Map<String, Object> m, String... v) {
        String[] keys = {"prompt.unregistered", "prompt.unauthenticated", "prompt.authenticated", "prompt.not_loaded", "prompt.totp_pending",
                "result.success", "result.already_registered", "result.username_taken", "result.invalid_username", "result.not_registered",
                "result.already_authenticated", "result.not_authenticated", "result.not_joined", "result.invalid_password", "result.passwords_do_not_match",
                "result.invalid_password_format", "result.too_many_attempts", "result.locked_out", "result.totp_required", "result.totp_invalid",
                "result.totp_already_enabled", "result.totp_not_enabled", "result.totp_enabled", "result.totp_disabled", "result.totp_setup_required",
                "result.recovery_code_invalid", "result.dialog_preference_updated", "result.dialog_preference_disabled", "status.authenticated", "status.unauthenticated",
                "status.unregistered", "status.not_loaded", "access.blocked", "access.auth_server_missing", "access.database", "access.ip_online_limit",
                "access.ip_registered_limit", "access.backend_missing", "access.banned", "no-permission", "player-not-found"};
        for (int i = 0; i < keys.length; i++) m.put(keys[i], v[i]);
    }

    private static void putAdmin(Map<String, Object> m, String... v) {
        String[] keys = {"admin.usage", "admin.commands.unregister", "admin.commands.changepassword", "admin.commands.forcelogin",
                "admin.commands.forceregister", "admin.commands.forcepremium", "admin.commands.migrate", "admin.unregister.success",
                "admin.changepassword.success", "admin.forcelogin.success", "admin.forceregister.success", "admin.forcepremium.success",
                "admin.migrate.success", "admin.migrate.error"};
        for (int i = 0; i < keys.length; i++) m.put(keys[i], v[i]);
    }

    private static void putUi(Map<String, Object> m, String... v) {
        String[] keys = {"logout.disconnect", "unregister.disconnect", "totp.secret", "totp.uri", "totp.recovery", "totp.confirm",
                 "dialog.register.title", "dialog.register.description", "dialog.register.password", "dialog.register.repeat", "dialog.register.button", "dialog.login.title",
                 "dialog.login.description", "dialog.login.password", "dialog.login.button", "reminder.login", "reminder.register", "kick.timeout", "display.title",
                "display.subtitle", "display.actionbar", "display.bossbar"};
        for (int i = 0; i < keys.length; i++) m.put(keys[i], v[i]);
    }

    private static String normalize(String locale) {
        String value = locale == null ? "ru" : locale.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z]{2}") ? value : "ru";
    }
}
