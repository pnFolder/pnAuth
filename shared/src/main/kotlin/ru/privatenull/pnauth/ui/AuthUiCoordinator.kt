package ru.privatenull.pnauth.ui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.command.AuthCommandRequest
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.command.CommandContext
import ru.privatenull.pnauth.command.CommandRegistry
import ru.privatenull.pnauth.command.CommandService
import ru.privatenull.pnauth.command.CommandSpec
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.dialog.AuthDialogFormFactory
import ru.privatenull.pnauth.dialog.DialogHandle
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.Player
import ru.privatenull.pnauth.security.ClickCaptchaService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Owns the complete platform-independent authentication UI lifecycle.
 * Platform modules only forward native player events and the internal command.
 */
class AuthUiCoordinator(
    private val auth: AuthApi,
    private val commands: AuthCommandService,
    private val messages: AuthMessages,
    private val features: FeatureSettings,
    private val maxPasswordLength: Int,
    private val authServer: String,
    private val platform: Platform,
    private val renderer: AuthUiRenderer,
    commandRegistry: CommandRegistry,
    private val diagnostics: Consumer<String>
) : AutoCloseable {

    private val captcha: ClickCaptchaService = ClickCaptchaService(features.captcha)
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val submissions = ConcurrentHashMap<UUID, Submission>()
    private val dialogs = ConcurrentHashMap<UUID, DialogHandle>()
    private val protocols = ConcurrentHashMap<UUID, Int>()
    private val commandService: CommandService = UiCommandService()
    private val commandRegistration: AutoCloseable = commandRegistry.register(commandService)

    /** Returns false when the adapter should display the command fallback. */
    fun show(playerId: UUID, status: AuthStatus, protocol: Int): Boolean {
        protocols[playerId] = protocol
        val player = player(playerId)
        if (player == null) {
            diagnostics.accept("[pnAuth-DEBUG] AuthUiCoordinator.show: player is NULL for $playerId")
            return false
        }
        val isOnAuth = onAuthServer(player)
        diagnostics.accept("[pnAuth-DEBUG] AuthUiCoordinator.show: player=${player.username()}, onAuthServer=$isOnAuth, currentServer=${player.currentServer().orElse("none")}, authServer=$authServer")
        if (!isOnAuth) return false
        val session = sessions.computeIfAbsent(playerId) { Session() }
        val running = submissions[playerId]
        if (running != null && running.session == session) return true
        clear(playerId)
        if (!passwordStage(status)) {
            diagnostics.accept("[pnAuth-DEBUG] AuthUiCoordinator.show: status $status is NOT passwordStage")
            return false
        }
        val isSupported = player.dialogs().supported(player)
        val useDialog = auth.shouldUseDialog(playerId, protocol, isSupported)
        diagnostics.accept("[pnAuth-DEBUG] AuthUiCoordinator.show: supported=$isSupported, shouldUseDialog=$useDialog, protocol=$protocol")
        if (!useDialog) return false
        if (!captcha.verified(playerId)) {
            sendCaptcha(player)
            return true
        }
        diagnostics.accept("[pnAuth-DEBUG] AuthUiCoordinator.show: calling open() for ${player.username()}")
        open(player, status, null, session)
        return true
    }

    fun allowAuthenticationCommand(playerId: UUID): Boolean = !requiresCaptcha(playerId)

    fun requestCaptcha(playerId: UUID) {
        val player = player(playerId)
        if (player != null && requiresCaptcha(playerId)) sendCaptcha(player)
    }

    private fun handleCommand(playerId: UUID, args: List<String>) {
        val player = player(playerId)
        if (player == null || !onAuthServer(player) || auth.isAuthenticated(playerId)) return
        if (args.size == 1 && args[0].equals("open", ignoreCase = true)) {
            show(playerId, auth.status(playerId), protocols.getOrDefault(playerId, Int.MAX_VALUE))
            return
        }
        if (args.size != 2 || !args[0].equals("captcha", ignoreCase = true)) return
        val result = captcha.verify(playerId, args[1])
        if (result == ClickCaptchaService.Result.SUCCESS) {
            player.sendMessage(renderer.render("captcha.success"))
            show(playerId, auth.status(playerId), protocols.getOrDefault(playerId, Int.MAX_VALUE))
            return
        }
        val key = when (result) {
            ClickCaptchaService.Result.INVALID -> "captcha.invalid"
            ClickCaptchaService.Result.EXPIRED -> "captcha.expired"
            ClickCaptchaService.Result.LOCKED -> "captcha.locked"
            else -> "captcha.invalid"
        }
        player.sendMessage(renderer.render(key))
        if (result != ClickCaptchaService.Result.INVALID) {
            player.sendMessage(
                renderer.render("captcha.retry")
                    .clickEvent(ClickEvent.runCommand(UI_COMMAND + " open"))
            )
        }
    }

    fun clear(playerId: UUID) {
        val handle = dialogs.remove(playerId)
        handle?.close()
    }

    fun clearSession(playerId: UUID) {
        val removed = sessions.remove(playerId)
        val submission = submissions[playerId]
        if (submission != null && submission.session == removed) submissions.remove(playerId, submission)
        captcha.clear(playerId)
        protocols.remove(playerId)
        clear(playerId)
    }

    override fun close() {
        dialogs.values.forEach { it.close() }
        dialogs.clear()
        submissions.clear()
        sessions.clear()
        captcha.clearAll()
        protocols.clear()
        try {
            commandRegistration.close()
        } catch (exception: Exception) {
            throw IllegalStateException("Could not unregister authentication UI commands", exception)
        }
    }

    private fun open(player: Player, status: AuthStatus, notice: Component?, session: Session) {
        val register = status == AuthStatus.UNREGISTERED
        val command = if (register) "register" else "login"
        val content = AuthDialogFormFactory.Content(
            renderer.render(if (register) "dialog.register.title" else "dialog.login.title"),
            renderer.render(if (register) "dialog.register.description" else "dialog.login.description"), notice,
            renderer.render(if (register) "dialog.register.password" else "dialog.login.password"),
            if (register) renderer.render("dialog.register.repeat") else null,
            renderer.render(if (register) "dialog.register.button" else "dialog.login.button")
        )
        val form = AuthDialogFormFactory.create(
            if (register) AuthDialogFormFactory.Mode.REGISTER else AuthDialogFormFactory.Mode.LOGIN,
            features.repeatPasswordWhenRegister, maxPasswordLength, content,
            { credentials -> submit(player.uniqueId(), command, credentials, session) },
            { closeWithError(player.uniqueId(), messages.text("operation-error")) }
        )
        clear(player.uniqueId())
        val handle = player.dialogs().show(player, form)
        dialogs[player.uniqueId()] = handle
    }

    private fun submit(
        playerId: UUID, command: String, credentials: AuthDialogFormFactory.Credentials,
        session: Session
    ) {
        dialogs.remove(playerId)
        val player = player(playerId)
        diagnostics.accept("[auth-ui] Received $command form response for $playerId")
        if (player == null) {
            diagnostics.accept("[auth-ui] Response ignored because the player is no longer available")
            return
        }
        if (sessions[playerId] != session) {
            diagnostics.accept("[auth-ui] Response ignored because its UI session is stale")
            return
        }
        if (!onAuthServer(player)) {
            closeWithError(playerId, messages.text("operation-error"))
            return
        }
        if (auth.isAuthenticated(playerId)) {
            player.sendMessage(renderer.render("auth.success"))
            return
        }
        if (!captcha.verified(playerId)) {
            sendCaptcha(player)
            return
        }
        val submission = Submission(session)
        if (submissions.putIfAbsent(playerId, submission) != null) return
        val current = auth.status(playerId)
        if ((command == "register" && current != AuthStatus.UNREGISTERED)
            || (command == "login" && current != AuthStatus.UNAUTHENTICATED)
        ) {
            submissions.remove(playerId, submission)
            showNotice(playerId, messages.prompt(current))
            return
        }
        val arguments: List<String> = if (command == "register")
            listOf(credentials.password, credentials.confirmation)
        else
            listOf(credentials.password)
        commands.execute(
            AuthCommandRequest(playerId, player.username(), command, arguments) { player.hasPermission(it) }
        ).whenComplete { output, error ->
            diagnostics.accept(
                "[auth-ui] " + command + " operation completed for " + playerId +
                        (if (error == null) "" else " with an error")
            )
            dispatchFinish(playerId, submission, output, error)
        }
    }

    private fun dispatchFinish(
        playerId: UUID, submission: Submission,
        output: List<String>?, error: Throwable?
    ) {
        val completion = Runnable {
            diagnostics.accept("[auth-ui] Starting result delivery for $playerId")
            try {
                finish(playerId, submission, output, error)
            } catch (failure: Throwable) {
                diagnostics.accept(
                    "[auth-ui] Result delivery crashed: " +
                            failure.javaClass.name + ": " + failure.message
                )
                submissions.remove(playerId, submission)
                closeWithError(playerId, messages.text("operation-error"))
            }
        }
        val current = player(playerId)
        if (current == null) {
            completion.run()
            return
        }
        try {
            current.scheduler().execute(current, completion)
        } catch (schedulingFailure: RuntimeException) {
            diagnostics.accept("[auth-ui] Platform executor rejected result delivery; running immediately")
            completion.run()
        }
    }

    private fun finish(
        playerId: UUID, submission: Submission,
        output: List<String>?, error: Throwable?
    ) {
        if (!submissions.remove(playerId, submission)) {
            diagnostics.accept("[auth-ui] Completion ignored because this exact submission was already handled")
            return
        }
        try {
            val player = player(playerId)
            if (player == null) {
                diagnostics.accept("[auth-ui] Completion cannot be delivered because the player left")
                return
            }
            if (error != null) {
                diagnostics.accept("[auth-ui] Delivering operation-error to $playerId")
                closeWithError(playerId, messages.text("operation-error"))
            } else if (auth.isAuthenticated(playerId)) {
                clear(playerId)
                val success = if (output.isNullOrEmpty()) messages.text("auth.success") else output[0]
                player.sendMessage(renderer.renderText(success))
                diagnostics.accept("[auth-ui] Delivered successful authentication result to $playerId")
            } else {
                val notice = if (output.isNullOrEmpty()) messages.prompt(auth.status(playerId)) else output[0]
                val next = auth.status(playerId)
                if (passwordStage(next)) closeWithError(playerId, notice)
                else {
                    clear(playerId)
                    player.sendMessage(renderer.renderText(notice))
                }
                diagnostics.accept("[auth-ui] Delivered rejected authentication result to $playerId")
            }
        } catch (exception: RuntimeException) {
            diagnostics.accept(
                "[auth-ui] Failed to deliver authentication result: " +
                        exception.javaClass.simpleName + ": " + exception.message
            )
            closeWithError(playerId, messages.text("operation-error"))
        }
    }

    private fun showNotice(playerId: UUID, notice: String) {
        val player = player(playerId)
        val status = auth.status(playerId)
        if (player != null && passwordStage(status)) {
            open(
                player, status, renderer.renderText(notice),
                sessions.computeIfAbsent(playerId) { Session() }
            )
        }
    }

    private fun closeWithError(playerId: UUID, error: String) {
        clear(playerId)
        val player = player(playerId) ?: return
        player.sendMessage(
            renderer.render("dialog.error", mapOf("error" to error))
                .append(Component.space()).append(
                    renderer.render("dialog.retry")
                        .clickEvent(ClickEvent.runCommand(UI_COMMAND + " open"))
                        .hoverEvent(HoverEvent.showText(renderer.render("dialog.retry_hover")))
                )
        )
    }

    private fun sendCaptcha(player: Player) {
        val challenge = captcha.issue(player.uniqueId())
        player.sendMessage(renderer.render("captcha.prompt", mapOf("answer" to challenge.answer)))
        var options = Component.empty()
        for (option in challenge.options) {
            options = options.append(
                renderer.render("captcha.option", mapOf("value" to option.label))
                    .clickEvent(ClickEvent.runCommand(UI_COMMAND + " captcha " + option.token))
                    .hoverEvent(HoverEvent.showText(renderer.render("captcha.hover")))
            ).append(Component.space())
        }
        player.sendMessage(options)
    }

    private fun requiresCaptcha(playerId: UUID): Boolean {
        val player = player(playerId)
        return player != null && onAuthServer(player) && !captcha.verified(playerId)
                && !auth.isAuthenticated(playerId) && passwordStage(auth.status(playerId))
    }

    private fun player(playerId: UUID): Player? = platform.player(playerId).orElse(null)
    private fun onAuthServer(player: Player): Boolean {
        return player.connected() && player.currentServer()
            .map { server -> server.equals(authServer, ignoreCase = true) }.orElse(false)
    }

    private class Session
    private class Submission(val session: Session)

    private inner class UiCommandService : CommandService {
        override fun definitions(): List<CommandSpec> {
            return listOf(CommandSpec("_pnauthui", emptyList()))
        }

        override fun execute(context: CommandContext): CompletionStage<List<String>> {
            if (context.source.isPlayer()) {
                val uid = context.source.uniqueId()
                if (uid != null) handleCommand(uid, context.arguments)
            }
            return CompletableFuture.completedFuture(emptyList())
        }

        override fun suggest(context: CommandContext): List<String> = emptyList()
    }

    companion object {
        private const val UI_COMMAND = "/_pnauthui"
        private fun passwordStage(status: AuthStatus): Boolean {
            return status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED
        }
    }
}
