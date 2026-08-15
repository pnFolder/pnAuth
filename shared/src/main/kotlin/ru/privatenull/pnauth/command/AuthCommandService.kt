package ru.privatenull.pnauth.command

import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthResult
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.api.DialogPreference
import ru.privatenull.pnauth.api.TotpSetup
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.event.BroadcastRequestedEvent
import ru.privatenull.pnauth.extension.AuthOperationRejectedException
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.storage.AuthMigrationService
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Platform-independent command implementation used by both proxy adapters. */
class AuthCommandService @JvmOverloads constructor(
    private val api: AuthApi,
    private val messages: AuthMessages,
    actions: AuthPlatformBridge? = AuthPlatformBridge.NONE,
    private val migration: AuthMigrationService? = null,
    private val features: FeatureSettings = FeatureSettings.defaults()
) : CommandService {

    private val platformEvents: AuthPlatformEventAdapter? =
        if (actions == null || actions === AuthPlatformBridge.NONE) null
        else AuthPlatformEventAdapter(api.events(), actions)

    override fun definitions(): List<CommandSpec> {
        return listOf(
            CommandSpec("auth", listOf("pnauth")),
            CommandSpec("register", listOf("reg")),
            CommandSpec("login", listOf("l")),
            CommandSpec("logout", emptyList()),
            CommandSpec("changepassword", listOf("changepass")),
            CommandSpec("totp", listOf("2fa")),
            CommandSpec("unregister", listOf("unreg")),
            CommandSpec("status", emptyList())
        )
    }

    fun commands(): List<CommandSpec> = definitions()

    override fun execute(context: CommandContext): CompletionStage<List<String>> {
        val source = context.source
        return execute(
            AuthCommandRequest(
                source.uniqueId(),
                source.username(),
                context.command,
                context.arguments,
                { permission -> source.hasPermission(permission) }
            )
        )
    }

    fun execute(request: AuthCommandRequest): CompletionStage<List<String>> {
        var command = normalize(request.command)
        var args = request.arguments
        val adminRoot = command == "auth" || command == "pnauth"
        if (adminRoot) {
            if (args.isEmpty()) {
                return completed(if (request.isPlayer()) help() else adminHelp(request))
            }
            command = normalize(args[0])
            args = args.subList(1, args.size)
        }

        if (adminRoot && isAdminCommand(command)) {
            return executeAdmin(request, command, args)
        }
        if (!request.isPlayer()) {
            return completed(messages.text("only-player"))
        }

        val uniqueId = request.uniqueId!!
        val username = request.username ?: ""
        val operation: CompletionStage<AuthResult> = when (command) {
            "register", "reg" -> {
                val expectedArguments = if (features.repeatPasswordWhenRegister) 2 else 1
                if (args.size != expectedArguments) {
                    return completed(
                        messages.text(
                            if (features.repeatPasswordWhenRegister) "usage.register" else "usage.register-single"
                        )
                    )
                }
                api.register(
                    uniqueId,
                    username,
                    args[0],
                    if (features.repeatPasswordWhenRegister) args[1] else args[0]
                )
            }
            "login", "l" -> {
                if (args.size != 1) {
                    return completed(messages.text("usage.login"))
                }
                api.login(uniqueId, args[0])
            }
            "logout" -> {
                if (args.isNotEmpty()) {
                    return completed(messages.text("usage.logout"))
                }
                api.logout(uniqueId)
            }
            "changepassword", "changepass" -> {
                if (args.size != 2) {
                    return completed(messages.text("usage.changepassword"))
                }
                api.changePassword(uniqueId, args[0], args[1])
            }
            "unregister", "unreg" -> {
                if (args.size != 1) return completed(messages.text("usage.unregister"))
                api.unregister(uniqueId, args[0])
            }
            "premium" -> {
                return completed(messages.text("no-permission"))
            }
            "ui", "dialogs" -> {
                if (args.size != 1) return completed(messages.text("usage.ui"))
                val preference = when (normalize(args[0])) {
                    "auto" -> DialogPreference.AUTO
                    "on", "enable", "enabled" -> DialogPreference.ENABLED
                    "off", "disable", "disabled", "command", "commands" -> DialogPreference.DISABLED
                    else -> null
                } ?: return completed(messages.text("usage.ui"))
                api.setDialogPreference(uniqueId, preference)
            }
            "totp", "2fa" -> {
                if (args.isEmpty()) return completed(messages.text("usage.totp"))
                val action = normalize(args[0])
                if (action == "enable") {
                    if (args.size != 2) return completed(messages.text("usage.totp"))
                    val password = args[1]
                    return api.beginTotpSetup(uniqueId, password, features.totpIssuer)
                        .thenApply { setup -> totpSetupMessages(setup) }
                        .exceptionally { error ->
                            val cause = if (error is java.util.concurrent.CompletionException) error.cause else error
                            if (cause is AuthOperationRejectedException) {
                                listOf(resultMessage(cause.result))
                            } else {
                                listOf(messages.text("operation-error"))
                            }
                        }
                }
                if (action == "verify" && args.size == 2) {
                    api.verifyTotp(uniqueId, args[1])
                } else if (action == "disable" && args.size == 3) {
                    api.disableTotp(uniqueId, args[1], args[2])
                } else {
                    return completed(messages.text("usage.totp"))
                }
            }
            "status" -> {
                if (args.isNotEmpty()) {
                    return completed(messages.text("usage.status"))
                }
                return completed(statusMessage(api.status(uniqueId)))
            }
            else -> {
                return completed(help())
            }
        }

        return operation.handle { result, error ->
            if (error != null) return@handle listOf(messages.text("operation-error"))
            if (result == AuthResult.SUCCESS) {
                if (api.isAuthenticated(uniqueId)) {
                    return@handle listOf(messages.text("auth.success"))
                }
            }
            listOf(resultMessage(result))
        }
    }

    override fun suggest(context: CommandContext): List<String> {
        return suggest(context.command, context.arguments)
    }

    fun suggest(command: String, arguments: List<String>): List<String> {
        val normalized = normalize(command)
        if ((normalized == "auth" || normalized == "pnauth") && arguments.isEmpty()) {
            return listOf("unregister", "changepassword", "forcelogin", "forceregister", "forcepremium", "broadcast", "migrate")
        }
        if (normalized == "auth" || normalized == "pnauth") {
            val prefix = if (arguments.isEmpty()) "" else normalize(arguments[arguments.size - 1])
            return listOf("unregister", "changepassword", "forcelogin", "forceregister", "forcepremium", "broadcast", "migrate")
                .filter { it.startsWith(prefix) }
        }
        return emptyList()
    }

    fun prompt(status: AuthStatus): String {
        return messages.prompt(status)
    }

    private fun resultMessage(result: AuthResult): String {
        return messages.text("result." + result.name.lowercase(Locale.ROOT))
    }

    private fun statusMessage(status: AuthStatus): String {
        return messages.text("status." + status.name.lowercase(Locale.ROOT))
    }

    private fun help(): List<String> {
        return messages.lines("help")
    }

    private fun totpSetupMessages(setup: TotpSetup): List<String> {
        return listOf(
            messages.text("totp.secret", java.util.Map.of("secret", setup.secret)),
            messages.text("totp.uri", java.util.Map.of("uri", setup.provisioningUri)),
            messages.text("totp.recovery", java.util.Map.of("codes", java.lang.String.join(", ", setup.recoveryCodes))),
            messages.text("totp.confirm")
        )
    }

    private fun executeAdmin(request: AuthCommandRequest, command: String, args: List<String>): CompletionStage<List<String>> {
        val permission = "pnauth.admin.commands." + when (command) {
            "unregister", "unreg" -> "unregister"
            "changepassword", "changepass" -> "changepassword"
            "forcelogin" -> "forcelogin"
            "forceregister", "forcereg", "register" -> "forceregister"
            "forcepremium", "premium" -> "forcepremium"
            else -> command
        }
        if (!request.hasPermission(permission)) {
            return completed(messages.text("no-permission"))
        }
        if (command == "broadcast") {
            if (args.isEmpty()) return completed(messages.text("admin.commands.broadcast"))
            api.events().publish(BroadcastRequestedEvent(java.lang.String.join(" ", args)))
            return completed(messages.text("admin.broadcast.success"))
        }
        if (command == "unregister" || command == "unreg") {
            if (args.size != 1) return completed(messages.text("admin.commands.unregister"))
            val username = args[0]
            return api.unregister(username).thenApply { result ->
                listOf(adminResult("unregister", result, username))
            }
        }
        if (command == "changepassword" || command == "changepass") {
            if (args.size != 2) return completed(messages.text("admin.commands.changepassword"))
            return api.adminChangePassword(args[0], args[1])
                .thenApply { result -> listOf(adminResult("changepassword", result, args[0])) }
        }
        if (command == "forcelogin") {
            if (args.size != 1) return completed(messages.text("admin.commands.forcelogin"))
            val username = args[0]
            return api.forceLogin(username).thenApply { result ->
                listOf(adminResult("forcelogin", result, username))
            }
        }
        if (command == "forcepremium" || command == "premium") {
            if (args.size != 1) return completed(messages.text("admin.commands.forcepremium"))
            return api.togglePremium(args[0]).thenApply { result -> listOf(adminResult("forcepremium", result, args[0])) }
        }
        if (command == "forceregister" || command == "forcereg" || command == "register") {
            if (args.size != 2) return completed(messages.text("admin.commands.forceregister"))
            return api.forceRegister(args[0], args[1])
                .thenApply { result -> listOf(adminResult("forceregister", result, args[0])) }
        }
        if (command == "migrate") {
            if (migration == null || args.size < 2) return completed(messages.text("admin.commands.migrate"))
            try {
                val source = AuthMigrationService.Source.valueOf(args[0].uppercase(Locale.ROOT))
                val user = if (args.size > 2) args[2] else ""
                val password = if (args.size > 3) args[3] else ""
                return migration.migrate(source, args[1], user, password)
                    .thenApply { count -> listOf(messages.text("admin.migrate.success", java.util.Map.of("count", count.toString()))) }
                    .exceptionally { listOf(messages.text("admin.migrate.error")) }
            } catch (exception: IllegalArgumentException) {
                return completed(messages.text("admin.migrate.error"))
            }
        }
        return completed(help())
    }

    private fun isAdminCommand(command: String): Boolean {
        return command == "unregister" || command == "unreg" ||
                command == "changepassword" || command == "changepass" ||
                command == "forcelogin" || command == "forceregister" || command == "forcereg" ||
                command == "forcepremium" || command == "premium" || command == "register" ||
                command == "broadcast" || command == "migrate"
    }

    private fun adminHelp(request: AuthCommandRequest): List<String> {
        return listOf(messages.text("admin.usage"))
    }

    private fun adminResult(command: String, result: AuthResult, player: String): String {
        if (result == AuthResult.SUCCESS) {
            return messages.text("admin.$command.success", java.util.Map.of("player", player))
        }
        if (result == AuthResult.PLAYER_NOT_FOUND) return messages.text("player-not-found")
        return resultMessage(result)
    }

    companion object {
        private fun completed(vararg messages: String): CompletionStage<List<String>> {
            return CompletableFuture.completedFuture(listOf(*messages))
        }

        private fun completed(messages: List<String>): CompletionStage<List<String>> {
            return CompletableFuture.completedFuture(messages)
        }

        private fun normalize(value: String?): String {
            val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: ""
            return if (normalized.startsWith("/")) normalized.substring(1) else normalized
        }
    }
}
