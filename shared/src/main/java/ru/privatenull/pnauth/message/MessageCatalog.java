package ru.privatenull.pnauth.message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable built-in defaults for the editable Russian and English message files. */
public final class MessageCatalog {
    private MessageCatalog() {
    }

    public static Map<String, Object> defaults(String locale) {
        return normalize(locale).equals("ru") ? russian() : english();
    }

    private static Map<String, Object> english() {
        Map<String, Object> values = new LinkedHashMap<>();
        putBase(values, "This command is available only to players.", "The authentication operation could not be completed.",
                "Usage: /register <password> <confirmation>", "Usage: /register <password>", "Usage: /login <password>", "Usage: /logout",
                "Usage: /changepassword <old> <new>", "Usage: /status",
                "Usage: /totp enable <password> | verify <code> | disable <password> <code>",
                "Usage: /unregister <password>", "Usage: /premium", "Usage: /auth ui <auto|on|off>",
                List.of("&eAuthentication commands:", "&f/register <password> <confirmation>", "&f/login <password>",
                        "&f/logout", "&f/changepassword <old> <new>", "&f/totp enable <password> | verify <code> | disable <password> <code>", "&f/status"));
        putCommon(values,
                "Account is not registered. Use /register <password> <confirmation>.",
                "Enter your password with /login <password>.", "You are authenticated.",
                "Your profile is still loading; try again in a moment.", "Enter your 2FA code with /totp verify <code>.",
                "Done.", "This account is already registered.", "This username is already taken.", "The username is invalid.",
                "The account is not registered.", "You are already authenticated.", "Log in first.",
                "Your profile is not loaded yet; try again in a moment.", "Invalid password.", "Passwords do not match.",
                "The password length is invalid.", "Too many login attempts.", "Login is temporarily locked. Try again later.",
                "Enter your two-factor code: /totp verify <code>.", "Invalid two-factor code.",
                "Two-factor authentication is already enabled.", "Two-factor authentication is not enabled.",
                "Two-factor authentication enabled.", "Two-factor authentication disabled.",
                "Start setup with /totp enable first.", "Invalid recovery code.", "Dialog UI preference updated.",
                "Players cannot change the dialog UI preference.", "Status: authenticated.", "Status: login required.",
                "Status: registration required.", "Status: profile is loading.", "Authenticate first.",
                "&cAuthentication server was not found: {server}", "&cThe database could not verify this connection.",
                "Too many accounts are already connected from this IP.", "Too many accounts are registered from this IP.",
                "The backend server is unavailable.", "This connection is temporarily blocked.", "You do not have permission.", "Player not found.");
        putAdmin(values,
                "Usage: /auth <unregister|changepassword|forcelogin|forceregister|forcepremium|broadcast|migrate>",
                "Usage: /auth unregister <player>", "Usage: /auth changepassword <player> <password>",
                "Usage: /auth forcelogin <player>", "Usage: /auth forceregister <player> <password>",
                "Usage: /auth forcepremium <player>",
                "Usage: /auth migrate <TIAUTH|AUTHME|MCAUTH|LIMBOAUTH|NLOGIN> <JDBC URL> [user] [password]",
                "Usage: /auth broadcast <message>", "Account {player} was deleted.", "Password for {player} was changed.",
                "Player {player} was force-authenticated.", "Account for {player} was registered.",
                "Premium mode for {player} was changed.", "Imported accounts: {count}.", "Database migration failed.",
                "Announcement sent to all players.");
        values.put("result.operation_denied", "This operation was denied by a security policy.");
        values.put("result.additional_verification_required", "Additional verification is required. Check the linked service.");
        putUi(values,
                "You have logged out.", "Your account was deleted.", "2FA secret: {secret}", "Authenticator URI: {uri}",
                "Recovery codes: {codes}", "Confirm setup with /totp verify <code>", "Registration",
                "Create a password for this account.", "Password", "Repeat password", "Register", "Login",
                "Enter your password to continue.", "Password", "Log in", "Log in with /login <password>.",
                "Register with /register <password> <confirmation>.", "You did not authenticate in time.", "Authentication",
                "Enter your login command", "Authenticate to continue", "Waiting for authentication");
        putInteractive(values,
                "&8[&d&lpnAuth&8] &fSecurity check: click number &d{answer}&f:", "&8[&d{value}&8]",
                "&7Click to confirm", "&aSecurity check passed.", "&cWrong option. Try again.",
                "&cThe challenge expired. Request a new one.", "&cToo many incorrect attempts.", "&d[New challenge]",
                "&8[&d&lpnAuth&8] &c{error} &7The dialog was closed.", "&d[Try again]",
                "&7Click to reopen the dialog", "&8[&d&lpnAuth&8] &aYou have successfully logged in. Welcome!");
        values.put("auth.processing.title", "Authentication");
        values.put("auth.processing.subtitle", "&fChecking your password, please wait...");
        values.put("auth.processing.success.title", "&aLogin successful");
        values.put("auth.processing.success.subtitle", "&fWelcome back!");
        values.put("auth.processing.failure.title", "&cLogin failed");
        values.put("auth.processing.failure.subtitle", "&fPlease check your password and try again.");
        values.put("broadcast.message", "&8[&d&lAnnouncement&8] &f{message}");
        return values;
    }

    private static Map<String, Object> russian() {
        Map<String, Object> values = new LinkedHashMap<>();
        putBase(values, "Эта команда доступна только игрокам.", "Не удалось выполнить операцию авторизации.",
                "Использование: /register <пароль> <повтор>", "Использование: /register <пароль>", "Использование: /login <пароль>", "Использование: /logout",
                "Использование: /changepassword <старый> <новый>", "Использование: /status",
                "Использование: /totp enable <пароль> | verify <код> | disable <пароль> <код>",
                "Использование: /unregister <пароль>",
                "Использование: /premium", "Использование: /auth ui <auto|on|off>",
                List.of("&eКоманды авторизации:", "&f/register <пароль> <повтор>", "&f/login <пароль>",
                        "&f/logout", "&f/changepassword <старый> <новый>", "&f/totp enable <пароль> | verify <код> | disable <пароль> <код>", "&f/status"));
        putCommon(values,
                "Аккаунт не зарегистрирован. Используйте /register <пароль> <повтор>.",
                "Введите пароль: /login <пароль>.", "Вы авторизованы.",
                "Профиль загружается, повторите попытку через секунду.", "Введите код 2FA: /totp verify <код>.",
                "Готово.", "Этот аккаунт уже зарегистрирован.", "Это имя уже занято.", "Недопустимое имя игрока.",
                "Аккаунт не зарегистрирован.", "Вы уже авторизованы.", "Сначала войдите в аккаунт.",
                "Профиль ещё не загружен, повторите попытку через секунду.", "Неверный пароль.", "Пароли не совпадают.",
                "Недопустимая длина пароля.", "Слишком много попыток входа.", "Вход временно заблокирован. Попробуйте позже.",
                "Введите код двухфакторной аутентификации: /totp verify <код>.", "Неверный двухфакторный код.",
                "Двухфакторная аутентификация уже включена.", "Двухфакторная аутентификация не включена.",
                "Двухфакторная аутентификация включена.", "Двухфакторная аутентификация отключена.",
                "Сначала начните настройку командой /totp enable.", "Неверный код восстановления.",
                "Настройка dialog UI изменена.", "Игрокам запрещено менять настройку dialog UI.",
                "Статус: авторизован.", "Статус: требуется вход.", "Статус: требуется регистрация.",
                "Статус: профиль загружается.", "Сначала авторизуйтесь.",
                "&cСервер авторизации не найден: {server}", "&cНе удалось проверить подключение к базе данных.",
                "С этого IP уже подключено слишком много аккаунтов.", "С этого IP уже зарегистрировано слишком много аккаунтов.",
                "Игровой сервер недоступен.", "Это подключение временно заблокировано.", "Недостаточно прав.", "Игрок не найден.");
        putAdmin(values,
                "Использование: /auth <unregister|changepassword|forcelogin|forceregister|forcepremium|broadcast|migrate>",
                "Использование: /auth unregister <игрок>", "Использование: /auth changepassword <игрок> <пароль>",
                "Использование: /auth forcelogin <игрок>", "Использование: /auth forceregister <игрок> <пароль>",
                "Использование: /auth forcepremium <игрок>",
                "Использование: /auth migrate <TIAUTH|AUTHME|MCAUTH|LIMBOAUTH|NLOGIN> <JDBC URL> [user] [password]",
                "Использование: /auth broadcast <сообщение>", "Аккаунт игрока {player} удалён.",
                "Пароль игрока {player} изменён.", "Игрок {player} принудительно авторизован.",
                "Аккаунт игрока {player} зарегистрирован.", "Premium-режим игрока {player} изменён.",
                "Импортировано аккаунтов: {count}.", "Не удалось выполнить миграцию базы данных.",
                "Сообщение отправлено всем игрокам.");
        values.put("result.operation_denied", "Операция запрещена дополнительной политикой безопасности.");
        values.put("result.additional_verification_required", "Требуется дополнительное подтверждение. Проверьте привязанный сервис.");
        putUi(values,
                "Вы вышли из аккаунта.", "Ваш аккаунт удалён.", "Секрет 2FA: {secret}", "Ссылка для приложения: {uri}",
                "Коды восстановления: {codes}", "Подтвердите настройку: /totp verify <код>", "Регистрация",
                "Создайте пароль для этого аккаунта.", "Пароль", "Повторите пароль", "Зарегистрироваться", "Авторизация",
                "Введите пароль, чтобы продолжить.", "Пароль", "Войти", "Войдите: /login <пароль>.",
                "Зарегистрируйтесь: /register <пароль> <повтор>.", "Время на авторизацию истекло.", "Авторизация",
                "Введите команду входа", "Авторизуйтесь, чтобы продолжить", "Ожидание авторизации");
        putInteractive(values,
                "&8[&d&lpnAuth&8] &fПроверка безопасности: нажмите число &d{answer}&f:", "&8[&d{value}&8]",
                "&7Нажмите, чтобы подтвердить", "&aПроверка пройдена.", "&cНеверный вариант. Попробуйте ещё раз.",
                "&cПроверка устарела. Получите новую.", "&cСлишком много неверных попыток.", "&d[Пройти проверку]",
                "&8[&d&lpnAuth&8] &c{error} &7Окно закрыто.", "&d[Повторить вход]",
                "&7Нажмите, чтобы снова открыть окно", "&8[&d&lpnAuth&8] &aВы успешно авторизовались. Добро пожаловать!");
        values.put("auth.processing.title", "Авторизация");
        values.put("auth.processing.subtitle", "&fПроверяем пароль, пожалуйста, подождите...");
        values.put("auth.processing.success.title", "&aВход выполнен");
        values.put("auth.processing.success.subtitle", "&fДобро пожаловать!");
        values.put("auth.processing.failure.title", "&cНе удалось войти");
        values.put("auth.processing.failure.subtitle", "&fПроверьте пароль и попробуйте ещё раз.");
        values.put("broadcast.message", "&8[&d&lОбъявление&8] &f{message}");
        return values;
    }

    private static void putBase(Map<String, Object> values, String onlyPlayer, String operationError,
                                String register, String registerSingle, String login, String logout, String changePassword, String status,
                                String totp, String unregister, String premium, String ui, List<String> help) {
        put(values, "only-player", onlyPlayer, "operation-error", operationError, "usage.register", register,
                "usage.register-single", registerSingle,
                "usage.login", login, "usage.logout", logout, "usage.changepassword", changePassword,
                "usage.status", status, "usage.totp", totp, "usage.unregister", unregister,
                "usage.premium", premium, "usage.ui", ui);
        values.put("help", help);
    }

    private static void putCommon(Map<String, Object> values, String... entries) {
        String[] keys = {"prompt.unregistered", "prompt.unauthenticated", "prompt.authenticated", "prompt.not_loaded", "prompt.totp_pending",
                "result.success", "result.already_registered", "result.username_taken", "result.invalid_username", "result.not_registered",
                "result.already_authenticated", "result.not_authenticated", "result.not_joined", "result.invalid_password", "result.passwords_do_not_match",
                "result.invalid_password_format", "result.too_many_attempts", "result.locked_out", "result.totp_required", "result.totp_invalid",
                "result.totp_already_enabled", "result.totp_not_enabled", "result.totp_enabled", "result.totp_disabled", "result.totp_setup_required",
                "result.recovery_code_invalid", "result.dialog_preference_updated", "result.dialog_preference_disabled", "status.authenticated", "status.unauthenticated",
                "status.unregistered", "status.not_loaded", "access.blocked", "access.auth_server_missing", "access.database", "access.ip_online_limit",
                "access.ip_registered_limit", "access.backend_missing", "access.banned", "no-permission", "player-not-found"};
        put(values, keys, entries);
    }

    private static void putAdmin(Map<String, Object> values, String... entries) {
        String[] keys = {"admin.usage", "admin.commands.unregister", "admin.commands.changepassword", "admin.commands.forcelogin",
                "admin.commands.forceregister", "admin.commands.forcepremium", "admin.commands.migrate", "admin.commands.broadcast",
                "admin.unregister.success", "admin.changepassword.success", "admin.forcelogin.success", "admin.forceregister.success",
                "admin.forcepremium.success", "admin.migrate.success", "admin.migrate.error", "admin.broadcast.success"};
        put(values, keys, entries);
    }

    private static void putUi(Map<String, Object> values, String... entries) {
        String[] keys = {"logout.disconnect", "unregister.disconnect", "totp.secret", "totp.uri", "totp.recovery", "totp.confirm",
                "dialog.register.title", "dialog.register.description", "dialog.register.password", "dialog.register.repeat", "dialog.register.button",
                "dialog.login.title", "dialog.login.description", "dialog.login.password", "dialog.login.button", "reminder.login",
                "reminder.register", "kick.timeout", "display.title", "display.subtitle", "display.actionbar", "display.bossbar"};
        put(values, keys, entries);
    }

    private static void putInteractive(Map<String, Object> values, String... entries) {
        String[] keys = {"captcha.prompt", "captcha.option", "captcha.hover", "captcha.success", "captcha.invalid", "captcha.expired",
                "captcha.locked", "captcha.retry", "dialog.error", "dialog.retry", "dialog.retry_hover", "auth.success"};
        put(values, keys, entries);
    }

    private static void put(Map<String, Object> values, String... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("Message entries must be key/value pairs");
        for (int index = 0; index < entries.length; index += 2) values.put(entries[index], entries[index + 1]);
    }

    private static void put(Map<String, Object> values, String[] keys, String[] entries) {
        if (keys.length != entries.length) throw new IllegalArgumentException("Message key/value counts differ");
        for (int index = 0; index < keys.length; index++) values.put(keys[index], entries[index]);
    }

    private static String normalize(String locale) {
        String value = locale == null ? "ru" : locale.trim().toLowerCase(Locale.ROOT);
        return value.equals("ru") || value.equals("en") ? value : "ru";
    }
}
