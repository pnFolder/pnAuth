package ru.privatenull.pnauth.message

import java.util.LinkedHashMap
import java.util.Locale

/** Immutable built-in defaults for the editable Russian and English message files. */
object MessageCatalog {

    @JvmStatic
    fun defaults(locale: String?): Map<String, Any> {
        return if (normalize(locale) == "ru") russian() else english()
    }

    private fun english(): Map<String, Any> {
        val values = LinkedHashMap<String, Any>()
        putBase(
            values, "This command is available only to players.", "The authentication operation could not be completed.",
            "Usage: /register <password> <confirmation>", "Usage: /register <password>", "Usage: /login <password>", "Usage: /logout",
            "Usage: /changepassword <old> <new>", "Usage: /status",
            "Usage: /totp enable <password> | verify <code> | disable <password> <code>",
            "Usage: /unregister <password>", "Usage: /premium", "Usage: /auth ui <auto|on|off>",
            listOf(
                "&eAuthentication commands:", "&f/register <password> <confirmation>", "&f/login <password>",
                "&f/logout", "&f/changepassword <old> <new>", "&f/totp enable <password> | verify <code> | disable <password> <code>", "&f/status"
            )
        )
        putCommon(
            values,
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
            "The backend server is unavailable.", "This connection is temporarily blocked.", "You do not have permission.", "Player not found."
        )
        putAdmin(
            values,
            "Usage: /auth <unregister|changepassword|forcelogin|forceregister|forcepremium|broadcast|migrate>",
            "Usage: /auth unregister <player>", "Usage: /auth changepassword <player> <password>",
            "Usage: /auth forcelogin <player>", "Usage: /auth forceregister <player> <password>",
            "Usage: /auth forcepremium <player>",
            "Usage: /auth migrate <TIAUTH|AUTHME|MCAUTH|LIMBOAUTH|NLOGIN> <JDBC URL> [user] [password]",
            "Usage: /auth broadcast <message>", "Account {player} was deleted.", "Password for {player} was changed.",
            "Player {player} was force-authenticated.", "Account for {player} was registered.",
            "Premium mode for {player} was changed.", "Imported accounts: {count}.", "Database migration failed.",
            "Announcement sent to all players."
        )
        values["result.operation_denied"] = "This operation was denied by a security policy."
        values["result.additional_verification_required"] = "Additional verification is required. Check the linked service."
        putUi(
            values,
            "You have logged out.", "Your account was deleted.", "2FA secret: {secret}", "Authenticator URI: {uri}",
            "Recovery codes: {codes}", "Confirm setup with /totp verify <code>", "Registration",
            "Create a password for this account.", "Password", "Repeat password", "Register", "Login",
            "Enter your password to continue.", "Password", "Log in", "Log in with /login <password>.",
            "Register with /register <password> <confirmation>.", "You did not authenticate in time.", "Authentication",
            "Enter your login command", "Authenticate to continue", "Waiting for authentication"
        )
        putInteractive(
            values,
            "&8[&d&lpnAuth&8] &fSecurity check: click number &d{answer}&f:", "&8[&d{value}&8]",
            "&7Click to confirm", "&aSecurity check passed.", "&cWrong option. Try again.",
            "&cThe challenge expired. Request a new one.", "&cToo many incorrect attempts.", "&d[New challenge]",
            "&8[&d&lpnAuth&8] &c{error} &7The dialog was closed.", "&d[Try again]",
            "&7Click to reopen the dialog", "&8[&d&lpnAuth&8] &aYou have successfully logged in. Welcome!"
        )
        values["broadcast.message"] = "&8[&d&lAnnouncement&8] &f{message}"
        values["title.login.success"] = "<gradient:#55FF55:#00AA00><bold>WELCOME BACK</bold></gradient>"
        values["subtitle.login.success"] = "<gradient:#AAFFAA:#FFFFFF>Successfully authenticated!</gradient>"
        values["title.register.success"] = "<gradient:#55FFFF:#00AAFF><bold>REGISTRATION SUCCESS</bold></gradient>"
        values["subtitle.register.success"] = "<gradient:#AAFFFF:#FFFFFF>Welcome to the server!</gradient>"
        values["title.error"] = "<gradient:#FF5555:#AA0000><bold>AUTHENTICATION ERROR</bold></gradient>"
        values["subtitle.error"] = "<gradient:#FFAAAA:#FFFFFF>{error}</gradient>"
        values["title.processing"] = "VERIFYING PASSWORD..."
        values["subtitle.processing"] = "<gradient:#8b5cf6:#38bdf8>Please wait a moment...</gradient>"
        return values
    }

    private fun russian(): Map<String, Any> {
        val values = LinkedHashMap<String, Any>()
        putBase(
            values, "Эта команда доступна только игрокам.", "Не удалось выполнить операцию авторизации.",
            "Использование: /register <пароль> <повтор>", "Использование: /register <пароль>", "Использование: /login <пароль>", "Использование: /logout",
            "Использование: /changepassword <старый> <новый>", "Использование: /status",
            "Использование: /totp enable <пароль> | verify <код> | disable <пароль> <код>",
            "Использование: /unregister <пароль>",
            "Использование: /premium", "Использование: /auth ui <auto|on|off>",
            listOf(
                "&eКоманды авторизации:", "&f/register <пароль> <повтор>", "&f/login <пароль>",
                "&f/logout", "&f/changepassword <старый> <новый>", "&f/totp enable <пароль> | verify <код> | disable <пароль> <код>", "&f/status"
            )
        )
        putCommon(
            values,
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
            "Игровой сервер недоступен.", "Это подключение временно заблокировано.", "Недостаточно прав.", "Игрок не найден."
        )
        putAdmin(
            values,
            "Использование: /auth <unregister|changepassword|forcelogin|forceregister|forcepremium|broadcast|migrate>",
            "Использование: /auth unregister <игрок>", "Использование: /auth changepassword <игрок> <пароль>",
            "Использование: /auth forcelogin <игрок>", "Использование: /auth forceregister <игрок> <пароль>",
            "Использование: /auth forcepremium <игрок>",
            "Использование: /auth migrate <TIAUTH|AUTHME|MCAUTH|LIMBOAUTH|NLOGIN> <JDBC URL> [user] [password]",
            "Использование: /auth broadcast <сообщение>", "Аккаунт игрока {player} удалён.",
            "Пароль игрока {player} изменён.", "Игрок {player} принудительно авторизован.",
            "Аккаунт игрока {player} зарегистрирован.", "Premium-режим игрока {player} изменён.",
            "Импортировано аккаунтов: {count}.", "Не удалось выполнить миграцию базы данных.",
            "Сообщение отправлено всем игрокам."
        )
        values["result.operation_denied"] = "Операция запрещена дополнительной политикой безопасности."
        values["result.additional_verification_required"] = "Требуется дополнительное подтверждение. Проверьте привязанный сервис."
        putUi(
            values,
            "Вы вышли из аккаунта.", "Ваш аккаунт удалён.", "Секрет 2FA: {secret}", "Ссылка для приложения: {uri}",
            "Коды восстановления: {codes}", "Подтвердите настройку: /totp verify <код>", "Регистрация",
            "Создайте пароль для этого аккаунта.", "Пароль", "Повторите пароль", "Зарегистрироваться", "Авторизация",
            "Введите пароль, чтобы продолжить.", "Пароль", "Войти", "Войдите: /login <пароль>.",
            "Зарегистрируйтесь: /register <пароль> <повтор>.", "Время на авторизацию истекло.", "Авторизация",
            "Введите команду входа", "Авторизуйтесь, чтобы продолжить", "Ожидание авторизации"
        )
        putInteractive(
            values,
            "&8[&d&lpnAuth&8] &fПроверка безопасности: нажмите число &d{answer}&f:", "&8[&d{value}&8]",
            "&7Нажмите, чтобы подтвердить", "&aПроверка пройдена.", "&cНеверный вариант. Попробуйте ещё раз.",
            "&cПроверка устарела. Получите новую.", "&cСлишком много неверных попыток.", "&d[Пройти проверку]",
            "&8[&d&lpnAuth&8] &c{error} &7Окно закрыто.", "&d[Повторить вход]",
            "&7Нажмите, чтобы снова открыть окно", "&8[&d&lpnAuth&8] &aВы успешно авторизовались. Добро пожаловать!"
        )
        values["broadcast.message"] = "&8[&d&lОбъявление&8] &f{message}"
        values["title.login.success"] = "<gradient:#55FF55:#00AA00><bold>УСПЕШНЫЙ ВХОД</bold></gradient>"
        values["subtitle.login.success"] = "<gradient:#AAFFAA:#FFFFFF>Вы успешно авторизовались!</gradient>"
        values["title.register.success"] = "<gradient:#55FFFF:#00AAFF><bold>РЕГИСТРАЦИЯ ЗАВЕРШЕНА</bold></gradient>"
        values["subtitle.register.success"] = "<gradient:#AAFFFF:#FFFFFF>Вы успешно зарегистрировались!</gradient>"
        values["title.error"] = "<gradient:#FF5555:#AA0000><bold>ОШИБКА ВХОДА</bold></gradient>"
        values["subtitle.error"] = "<gradient:#FFAAAA:#FFFFFF>{error}</gradient>"
        values["title.processing"] = "ПРОВЕРКА ПАРОЛЯ..."
        values["subtitle.processing"] = "<gradient:#8b5cf6:#38bdf8>Пожалуйста, подождите...</gradient>"
        return values
    }

    private fun putBase(
        values: MutableMap<String, Any>, onlyPlayer: String, operationError: String,
        register: String, registerSingle: String, login: String, logout: String, changePassword: String, status: String,
        totp: String, unregister: String, premium: String, ui: String, help: List<String>
    ) {
        put(
            values, "only-player", onlyPlayer, "operation-error", operationError, "usage.register", register,
            "usage.register-single", registerSingle,
            "usage.login", login, "usage.logout", logout, "usage.changepassword", changePassword,
            "usage.status", status, "usage.totp", totp, "usage.unregister", unregister,
            "usage.premium", premium, "usage.ui", ui
        )
        values["help"] = help
    }

    private fun putCommon(values: MutableMap<String, Any>, vararg entries: String) {
        val keys = arrayOf(
            "prompt.unregistered", "prompt.unauthenticated", "prompt.authenticated", "prompt.not_loaded", "prompt.totp_pending",
            "result.success", "result.already_registered", "result.username_taken", "result.invalid_username", "result.not_registered",
            "result.already_authenticated", "result.not_authenticated", "result.not_joined", "result.invalid_password", "result.passwords_do_not_match",
            "result.invalid_password_format", "result.too_many_attempts", "result.locked_out", "result.totp_required", "result.totp_invalid",
            "result.totp_already_enabled", "result.totp_not_enabled", "result.totp_enabled", "result.totp_disabled", "result.totp_setup_required",
            "result.recovery_code_invalid", "result.dialog_preference_updated", "result.dialog_preference_disabled", "status.authenticated", "status.unauthenticated",
            "status.unregistered", "status.not_loaded", "access.blocked", "access.auth_server_missing", "access.database", "access.ip_online_limit",
            "access.ip_registered_limit", "access.backend_missing", "access.banned", "no-permission", "player-not-found"
        )
        put(values, keys, entries)
    }

    private fun putAdmin(values: MutableMap<String, Any>, vararg entries: String) {
        val keys = arrayOf(
            "admin.usage", "admin.commands.unregister", "admin.commands.changepassword", "admin.commands.forcelogin",
            "admin.commands.forceregister", "admin.commands.forcepremium", "admin.commands.migrate", "admin.commands.broadcast",
            "admin.unregister.success", "admin.changepassword.success", "admin.forcelogin.success", "admin.forceregister.success",
            "admin.forcepremium.success", "admin.migrate.success", "admin.migrate.error", "admin.broadcast.success"
        )
        put(values, keys, entries)
    }

    private fun putUi(values: MutableMap<String, Any>, vararg entries: String) {
        val keys = arrayOf(
            "logout.disconnect", "unregister.disconnect", "totp.secret", "totp.uri", "totp.recovery", "totp.confirm",
            "dialog.register.title", "dialog.register.description", "dialog.register.password", "dialog.register.repeat", "dialog.register.button",
            "dialog.login.title", "dialog.login.description", "dialog.login.password", "dialog.login.button", "reminder.login",
            "reminder.register", "kick.timeout", "display.title", "display.subtitle", "display.actionbar", "display.bossbar"
        )
        put(values, keys, entries)
    }

    private fun putInteractive(values: MutableMap<String, Any>, vararg entries: String) {
        val keys = arrayOf(
            "captcha.prompt", "captcha.option", "captcha.hover", "captcha.success", "captcha.invalid", "captcha.expired",
            "captcha.locked", "captcha.retry", "dialog.error", "dialog.retry", "dialog.retry_hover", "auth.success"
        )
        put(values, keys, entries)
    }

    private fun put(values: MutableMap<String, Any>, vararg entries: String) {
        require(entries.size % 2 == 0) { "Message entries must be key/value pairs" }
        var index = 0
        while (index < entries.size) {
            values[entries[index]] = entries[index + 1]
            index += 2
        }
    }

    private fun put(values: MutableMap<String, Any>, keys: Array<String>, entries: Array<out String>) {
        require(keys.size == entries.size) { "Message key/value counts differ" }
        for (index in keys.indices) {
            values[keys[index]] = entries[index]
        }
    }

    private fun normalize(locale: String?): String {
        val value = if (locale == null) "ru" else locale.trim().lowercase(Locale.ROOT)
        return if (value == "ru" || value == "en") value else "ru"
    }
}
