package ru.privatenull.pnauth.velocity.dialog

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.command.AuthCommandRequest
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.dialog.AuthDialogFormFactory
import ru.privatenull.pnauth.dialog.DialogForm
import ru.privatenull.pnauth.dialog.DialogHandle
import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.platform.PnPlatform
import ru.privatenull.pnauth.platform.PnPlayer
import ru.privatenull.pnauth.security.ClickCaptchaService
import ru.privatenull.pnauth.velocity.VelocityMessages
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VelocityDialogCoordinator(
    private val proxy: ProxyServer,
    private val auth: AuthApi,
    private val commands: AuthCommandService,
    private val messages: AuthMessages,
    private val features: FeatureSettings,
    private val format: MessageFormat,
    private val maxPasswordLength: Int,
    private val proxySettings: ProxySettings,
    private val platform: PnPlatform
) : AutoCloseable {

    private val dialogs: PlayerDialogs = platform.dialogs()
    private val captcha = ClickCaptchaService(features.captcha)
    private val sessions: MutableMap<UUID, Session> = ConcurrentHashMap()
    private val submissions: MutableMap<UUID, Submission> = ConcurrentHashMap()
    private val activeDialogs: MutableMap<UUID, DialogHandle> = ConcurrentHashMap()

    init {
        proxy.commandManager.register(
            proxy.commandManager.metaBuilder("_pnauthui").build(),
            UiCommand()
        )
    }

    fun show(player: Player, status: AuthStatus): Boolean {
        val playerId = player.uniqueId
        val session = sessions.computeIfAbsent(playerId) { Session() }
        val running = submissions[playerId]
        if (running != null && running.session === session) return true
        clear(player)
        if (status != AuthStatus.UNREGISTERED && status != AuthStatus.UNAUTHENTICATED) return false
        if (!isOnAuthServer(player)) return false
        val pnPlayer = platform.player(playerId).orElse(null)
        val supported = pnPlayer != null && dialogs.supported(pnPlayer)
        if (!auth.shouldUseDialog(playerId, player.protocolVersion.protocol, supported)) return false
        if (!captcha.verified(playerId)) {
            sendCaptcha(player)
            return true
        }
        open(player, status, null, session)
        return true
    }

    fun clear(player: Player) {
        val handle = activeDialogs.remove(player.uniqueId)
        handle?.close()
    }

    fun clearSession(player: Player) {
        val playerId = player.uniqueId
        val removed = sessions.remove(playerId)
        val submission = submissions[playerId]
        if (submission != null && submission.session === removed) {
            submissions.remove(playerId, submission)
        }
        captcha.clear(playerId)
        clear(player)
    }

    fun available(): Boolean = true

    fun allowAuthenticationCommand(player: Player): Boolean = !requiresCaptcha(player)

    fun requestCaptcha(player: Player) {
        if (requiresCaptcha(player)) sendCaptcha(player)
    }

    override fun close() {
        activeDialogs.values.forEach { it.close() }
        activeDialogs.clear()
        submissions.clear()
        sessions.clear()
        captcha.clearAll()
        proxy.commandManager.unregister("_pnauthui")
    }

    private fun open(player: Player, status: AuthStatus, notice: Component?, session: Session) {
        val register = status == AuthStatus.UNREGISTERED
        val command = if (register) "register" else "login"
        val content = AuthDialogFormFactory.Content(
            component(if (register) "dialog.register.title" else "dialog.login.title"),
            component(if (register) "dialog.register.description" else "dialog.login.description"),
            notice,
            component(if (register) "dialog.register.password" else "dialog.login.password"),
            if (register) component("dialog.register.repeat") else null,
            component(if (register) "dialog.register.button" else "dialog.login.button")
        )
        val form = AuthDialogFormFactory.create(
            if (register) AuthDialogFormFactory.Mode.REGISTER else AuthDialogFormFactory.Mode.LOGIN,
            features.repeatPasswordWhenRegister, maxPasswordLength, content,
            { credentials -> submit(player, command, credentials, session) },
            { closeWithError(player, messages.text("operation-error")) }
        )
        val pnPlayer = platform.player(player.uniqueId)
            .orElseThrow { IllegalStateException("Player left before the dialog was shown") }
        val handle = dialogs.show(pnPlayer, form)
        val previous = activeDialogs.put(player.uniqueId, handle)
        previous?.close()
    }

    private fun submit(
        player: Player,
        command: String,
        credentials: AuthDialogFormFactory.Credentials,
        session: Session
    ) {
        val playerId = player.uniqueId
        activeDialogs.remove(playerId)
        if (sessions[playerId] !== session || !isOnAuthServer(player)
            || auth.isAuthenticated(playerId) || !captcha.verified(playerId)
        ) return
        val submission = Submission(session)
        if (submissions.putIfAbsent(playerId, submission) != null) return
        val current = auth.status(playerId)
        if ((command == "register" && current != AuthStatus.UNREGISTERED)
            || (command == "login" && current != AuthStatus.UNAUTHENTICATED)
        ) {
            submissions.remove(playerId, submission)
            showNotice(player, messages.prompt(current))
            return
        }
        val arguments = if (command == "register") {
            listOf(credentials.password, credentials.confirmation)
        } else {
            listOf(credentials.password)
        }
        showProcessingTitle(player)
        commands.execute(
            AuthCommandRequest(playerId, player.username, command, arguments) { player.hasPermission(it) }
        ).whenComplete { output, error ->
            finish(player, session, submission, output, error)
        }
    }

    private fun finish(
        player: Player,
        session: Session,
        submission: Submission,
        output: List<String>?,
        error: Throwable?
    ) {
        val playerId = player.uniqueId
        if (sessions[playerId] !== session || submissions[playerId] !== submission) return
        try {
            if (error != null) {
                closeWithError(player, messages.text("operation-error"))
            } else if (auth.isAuthenticated(playerId)) {
                clear(player)
                player.sendMessage(VelocityMessages.component(messages.text("auth.success"), format))
            } else {
                val notice = if (output.isNullOrEmpty()) messages.prompt(auth.status(playerId)) else output[0]
                val next = auth.status(playerId)
                if (next == AuthStatus.UNREGISTERED || next == AuthStatus.UNAUTHENTICATED) {
                    closeWithError(player, notice)
                } else {
                    clear(player)
                    player.sendMessage(VelocityMessages.component(notice, format))
                }
            }
        } finally {
            submissions.remove(playerId, submission)
        }
    }

    private fun showNotice(player: Player, notice: String) {
        val status = auth.status(player.uniqueId)
        if (status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED) {
            open(
                player, status, VelocityMessages.component(notice, format),
                sessions.computeIfAbsent(player.uniqueId) { Session() }
            )
        }
    }

    private fun closeWithError(player: Player, error: String) {
        clear(player)

        // Display error Title & Subtitle on screen
        val titleComp = VelocityMessages.component(messages.text("title.error"), format)
        val subtitleComp = VelocityMessages.component(messages.text("subtitle.error", mapOf("error" to error)), format)
        val titleObj = net.kyori.adventure.title.Title.title(
            titleComp,
            subtitleComp,
            net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofMillis(2500),
                java.time.Duration.ofMillis(500)
            )
        )
        player.showTitle(titleObj)

        player.sendMessage(
            VelocityMessages.component(messages.text("dialog.error", mapOf("error" to error)), format)
                .append(Component.space()).append(
                    VelocityMessages.component(messages.text("dialog.retry"), format)
                        .clickEvent(ClickEvent.runCommand("/_pnauthui open"))
                        .hoverEvent(
                            HoverEvent.showText(
                                VelocityMessages.component(messages.text("dialog.retry_hover"), format)
                            )
                        )
                )
        )
    }

    private fun showProcessingTitle(player: Player) {
        val frames = ru.privatenull.pnauth.display.ProcessingTitleAnimation.generateFrames(
            messages.text("title.processing"),
            ru.privatenull.pnauth.config.ProcessingTitleSettings.Animation.defaults()
        )
        val subtitle = VelocityMessages.component(messages.text("subtitle.processing"), format)
        if (frames.isNotEmpty()) {
            val titleObj = net.kyori.adventure.title.Title.title(
                VelocityMessages.component(frames[0], format),
                subtitle,
                net.kyori.adventure.title.Title.Times.times(
                    java.time.Duration.ZERO,
                    java.time.Duration.ofMillis(1000),
                    java.time.Duration.ofMillis(250)
                )
            )
            player.showTitle(titleObj)
        }
    }

    private fun component(key: String): Component {
        return VelocityMessages.component(messages.text(key), format)
    }

    private fun sendCaptcha(player: Player) {
        val challenge = captcha.issue(player.uniqueId)
        player.sendMessage(
            VelocityMessages.component(
                messages.text("captcha.prompt", mapOf("answer" to challenge.answer)), format
            )
        )
        var options = Component.empty()
        for (option in challenge.options) {
            options = options.append(
                VelocityMessages.component(
                    messages.text("captcha.option", mapOf("value" to option.label)), format
                )
                    .clickEvent(ClickEvent.runCommand("/_pnauthui captcha " + option.token))
                    .hoverEvent(HoverEvent.showText(VelocityMessages.component(messages.text("captcha.hover"), format)))
            ).append(Component.space())
        }
        player.sendMessage(options)
    }

    private fun handleUi(player: Player, arguments: Array<String>) {
        if (!isOnAuthServer(player)) return
        if (arguments.size == 1 && arguments[0].equals("open", ignoreCase = true)) {
            show(player, auth.status(player.uniqueId))
            return
        }
        if (arguments.size != 2 || !arguments[0].equals("captcha", ignoreCase = true)) return
        val result = captcha.verify(player.uniqueId, arguments[1])
        if (result == ClickCaptchaService.Result.SUCCESS) {
            player.sendMessage(VelocityMessages.component(messages.text("captcha.success"), format))
            show(player, auth.status(player.uniqueId))
        } else {
            val key = when (result) {
                ClickCaptchaService.Result.INVALID -> "captcha.invalid"
                ClickCaptchaService.Result.EXPIRED -> "captcha.expired"
                else -> "captcha.locked"
            }
            player.sendMessage(VelocityMessages.component(messages.text(key), format))
        }
    }

    private fun requiresCaptcha(player: Player): Boolean {
        if (!isOnAuthServer(player) || captcha.verified(player.uniqueId)
            || auth.isAuthenticated(player.uniqueId)
        ) return false
        val status = auth.status(player.uniqueId)
        return status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED
    }

    private fun isOnAuthServer(player: Player): Boolean {
        return player.currentServer.map { connection ->
            connection.serverInfo.name.equals(proxySettings.authServer, ignoreCase = true)
        }.orElse(false)
    }

    private inner class UiCommand : SimpleCommand {
        override fun execute(invocation: SimpleCommand.Invocation) {
            val player = invocation.source() as? Player
            if (player != null && !auth.isAuthenticated(player.uniqueId)) {
                handleUi(player, invocation.arguments())
            }
        }
    }

    private class Session
    private data class Submission(val session: Session)
}
