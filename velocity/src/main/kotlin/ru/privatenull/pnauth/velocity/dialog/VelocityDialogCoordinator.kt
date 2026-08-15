package ru.privatenull.pnauth.velocity.dialog

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.slf4j.Logger
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.command.AuthCommandRequest
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.security.ClickCaptchaService
import ru.privatenull.pnauth.velocity.VelocityMessages
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VelocityDialogCoordinator(
    private val proxy: ProxyServer,
    logger: Logger,
    private val auth: AuthApi,
    private val commands: AuthCommandService,
    private val messages: AuthMessages,
    private val features: FeatureSettings,
    private val format: MessageFormat,
    private val maxPasswordLength: Int,
    private val proxySettings: ProxySettings
) : AutoCloseable {

    private val dialogs: VelocityDialogService = VelocityDialogServiceFactory.create(proxy, logger) { player, actionId, values ->
        submit(player, actionId, values)
    }
    private val captcha: ClickCaptchaService = ClickCaptchaService(features.captcha)
    private val sessions: MutableMap<UUID, DialogSession> = ConcurrentHashMap()
    private val submissions: MutableMap<UUID, PendingSubmission> = ConcurrentHashMap()
    private val pendingDialogs: MutableMap<UUID, PendingDialog> = ConcurrentHashMap()

    init {
        proxy.commandManager.register(
            proxy.commandManager.metaBuilder("_pnauthui").build(),
            UiCommand()
        )
    }

    fun show(player: Player, status: AuthStatus): Boolean {
        val playerId = player.uniqueId
        val session = sessionFor(playerId)
        val activeSubmission = submissions[playerId]
        if (activeSubmission != null) {
            if (activeSubmission.session == session) return true
            submissions.remove(playerId, activeSubmission)
        }
        clearPendingDialog(playerId)
        if (status != AuthStatus.UNREGISTERED && status != AuthStatus.UNAUTHENTICATED) return false
        if (!isOnAuthServer(player)) return false
        val protocol = player.protocolVersion.protocol
        if (!auth.shouldUseDialog(player.uniqueId, protocol, dialogs.available())) return false
        if (!captcha.verified(player.uniqueId)) {
            sendCaptcha(player)
            return true
        }
        showDialog(player, status, null)
        return true
    }

    fun clear(player: Player) {
        clearPendingDialog(player.uniqueId)
        clearTransport(player)
    }

    fun clearSession(player: Player) {
        val playerId = player.uniqueId
        val session = sessions.remove(playerId)
        if (session != null) {
            clearPendingDialog(playerId, session)
            val submission = submissions[playerId]
            if (submission != null && submission.session == session) {
                submissions.remove(playerId, submission)
            }
        }
        captcha.clear(playerId)
        clearTransport(player)
    }

    fun available(): Boolean {
        return dialogs.available()
    }

    fun allowAuthenticationCommand(player: Player): Boolean {
        return !requiresCaptcha(player)
    }

    fun requestCaptcha(player: Player) {
        if (requiresCaptcha(player)) sendCaptcha(player)
    }

    override fun close() {
        submissions.clear()
        sessions.clear()
        pendingDialogs.clear()
        captcha.clearAll()
        proxy.commandManager.unregister("_pnauthui")
        dialogs.close()
    }

    private fun submit(player: Player, actionId: String, values: Map<String, String>) {
        val pending = consumeDialog(player, actionId) ?: return
        val playerId = player.uniqueId
        val submission = PendingSubmission(pending.session)
        val activeSubmission = submissions.putIfAbsent(playerId, submission)
        if (activeSubmission != null) {
            pendingDialogs.putIfAbsent(playerId, pending)
            return
        }
        if (!isCurrentSession(playerId, pending.session)) {
            submissions.remove(playerId, submission)
            return
        }
        val command = pending.command
        val current = auth.status(player.uniqueId)
        if (current == AuthStatus.AUTHENTICATED) {
            clear(player)
            submissions.remove(playerId, submission)
            return
        }
        if ((command == "register" && current != AuthStatus.UNREGISTERED)
            || (command == "login" && current != AuthStatus.UNAUTHENTICATED)
        ) {
            showNotice(player, messages.prompt(current))
            submissions.remove(playerId, submission)
            return
        }
        val password = values.getOrDefault("password", "")
        val arguments = if (command == "register") {
            listOf(
                password,
                if (features.repeatPasswordWhenRegister) values.getOrDefault("confirmation", "") else password
            )
        } else {
            listOf(password)
        }
        val request = AuthCommandRequest(
            player.uniqueId, player.username, command, arguments, player::hasPermission
        )
        commands.execute(request).whenComplete { output, error ->
            if (!isCurrentSession(playerId, pending.session) || submissions[playerId] != submission) return@whenComplete
            try {
                if (error != null) {
                    closeWithError(player, messages.text("operation-error"))
                    return@whenComplete
                }
                if (auth.isAuthenticated(playerId)) {
                    clear(player)
                    player.sendMessage(VelocityMessages.component(messages.text("auth.success"), format))
                    return@whenComplete
                }
                val notice = if (output == null || output.isEmpty()) messages.prompt(auth.status(playerId)) else output[0]
                val status = auth.status(playerId)
                if (status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED) {
                    closeWithError(player, notice)
                } else {
                    clear(player)
                    player.sendMessage(VelocityMessages.component(notice, format))
                }
            } finally {
                submissions.remove(playerId, submission)
            }
        }
    }

    private fun closeWithError(player: Player, error: String) {
        clear(player)
        val line = VelocityMessages.component(messages.text("dialog.error", mapOf("error" to error)), format)
            .append(Component.space())
            .append(
                VelocityMessages.component(messages.text("dialog.retry"), format)
                    .clickEvent(ClickEvent.runCommand("/_pnauthui open"))
                    .hoverEvent(
                        HoverEvent.showText(
                            VelocityMessages.component(messages.text("dialog.retry_hover"), format)
                        )
                    )
            )
        player.sendMessage(line)
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
            val button = VelocityMessages.component(
                messages.text("captcha.option", mapOf("value" to option.label)), format
            )
                .clickEvent(ClickEvent.runCommand("/_pnauthui captcha " + option.token))
                .hoverEvent(HoverEvent.showText(VelocityMessages.component(messages.text("captcha.hover"), format)))
            options = options.append(button).append(Component.space())
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
        when (result) {
            ClickCaptchaService.Result.SUCCESS -> {
                player.sendMessage(VelocityMessages.component(messages.text("captcha.success"), format))
                show(player, auth.status(player.uniqueId))
            }
            ClickCaptchaService.Result.INVALID -> player.sendMessage(VelocityMessages.component(messages.text("captcha.invalid"), format))
            ClickCaptchaService.Result.EXPIRED, ClickCaptchaService.Result.LOCKED -> {
                val key = if (result == ClickCaptchaService.Result.EXPIRED) "captcha.expired" else "captcha.locked"
                player.sendMessage(
                    VelocityMessages.component(messages.text(key), format)
                        .append(Component.space())
                        .append(
                            VelocityMessages.component(messages.text("captcha.retry"), format)
                                .clickEvent(ClickEvent.runCommand("/_pnauthui open"))
                        )
                )
            }
        }
    }

    private inner class UiCommand : SimpleCommand {
        override fun execute(invocation: SimpleCommand.Invocation) {
            val player = invocation.source() as? Player ?: return
            val arguments = invocation.arguments()
            if (!auth.isAuthenticated(player.uniqueId)) {
                handleUi(player, arguments)
            }
        }
    }

    private fun showNotice(player: Player, notice: String) {
        val status = auth.status(player.uniqueId)
        if (status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED) {
            showDialog(player, status, VelocityMessages.component(notice, format))
        }
    }

    private fun showDialog(player: Player, status: AuthStatus, notice: Component?) {
        val pending = issueDialog(player.uniqueId, status == AuthStatus.UNREGISTERED)
        try {
            dialogs.show(player, form(status, notice, pending.actionId))
        } catch (exception: RuntimeException) {
            pendingDialogs.remove(player.uniqueId, pending)
            throw exception
        }
    }

    private fun form(status: AuthStatus, notice: Component?, actionId: String): VelocityDialogService.DialogForm {
        val register = status == AuthStatus.UNREGISTERED
        val fields = if (register && features.repeatPasswordWhenRegister) {
            listOf(
                field("password", "dialog.register.password"),
                field("confirmation", "dialog.register.repeat")
            )
        } else {
            listOf(field("password", if (register) "dialog.register.password" else "dialog.login.password"))
        }
        return VelocityDialogService.DialogForm(
            component(if (register) "dialog.register.title" else "dialog.login.title"),
            notice,
            fields,
            component(if (register) "dialog.register.button" else "dialog.login.button"),
            actionId
        )
    }

    private fun field(key: String, messageKey: String): VelocityDialogService.TextField {
        return VelocityDialogService.TextField(key, component(messageKey), maxPasswordLength)
    }

    private fun component(key: String): Component {
        return VelocityMessages.component(messages.text(key), format)
    }

    private fun issueDialog(uniqueId: UUID, register: Boolean): PendingDialog {
        val pending = PendingDialog(
            "pnauth:" + (if (register) "register" else "login") + "-" + UUID.randomUUID().toString().replace("-", ""),
            if (register) "register" else "login", sessionFor(uniqueId)
        )
        pendingDialogs[uniqueId] = pending
        return pending
    }

    private fun consumeDialog(player: Player, actionId: String): PendingDialog? {
        if (!isOnAuthServer(player) || auth.isAuthenticated(player.uniqueId) || !captcha.verified(player.uniqueId)) {
            return null
        }
        val session = sessions[player.uniqueId] ?: return null
        val pending = pendingDialogs[player.uniqueId]
        if (pending == null || pending.session != session || pending.actionId != actionId) return null
        return if (pendingDialogs.remove(player.uniqueId, pending)) pending else null
    }

    private fun isOnAuthServer(player: Player): Boolean {
        return player.currentServer.map { connection ->
            connection.serverInfo.name.equals(proxySettings.authServer, ignoreCase = true)
        }.orElse(false)
    }

    private fun clearPendingDialog(uniqueId: UUID) {
        pendingDialogs.remove(uniqueId)
    }

    private fun clearPendingDialog(uniqueId: UUID, session: DialogSession) {
        val pending = pendingDialogs[uniqueId]
        if (pending != null && pending.session == session) {
            pendingDialogs.remove(uniqueId, pending)
        }
    }

    private fun clearTransport(player: Player) {
        if (dialogs.available()) dialogs.clear(player)
    }

    private fun sessionFor(uniqueId: UUID): DialogSession {
        return sessions.computeIfAbsent(uniqueId) { DialogSession() }
    }

    private fun isCurrentSession(uniqueId: UUID, session: DialogSession): Boolean {
        return sessions[uniqueId] === session
    }

    private fun requiresCaptcha(player: Player): Boolean {
        if (!isOnAuthServer(player) || captcha.verified(player.uniqueId) || auth.isAuthenticated(player.uniqueId)) {
            return false
        }
        val status = auth.status(player.uniqueId)
        return status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED
    }

    private class DialogSession

    private data class PendingSubmission(val session: DialogSession)

    private data class PendingDialog(val actionId: String, val command: String, val session: DialogSession)
}
