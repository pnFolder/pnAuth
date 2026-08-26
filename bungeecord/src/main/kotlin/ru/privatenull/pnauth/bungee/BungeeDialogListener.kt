package ru.privatenull.pnauth.bungee

import net.kyori.adventure.text.Component
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.PostLoginEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask
import net.md_5.bungee.event.EventHandler
import ru.privatenull.pnauth.api.AuthApi
import ru.privatenull.pnauth.api.AuthStatus
import ru.privatenull.pnauth.command.AuthCommandRequest
import ru.privatenull.pnauth.command.AuthCommandService
import ru.privatenull.pnauth.config.FeatureSettings
import ru.privatenull.pnauth.config.ProxySettings
import ru.privatenull.pnauth.config.ProcessingTitleSettings
import ru.privatenull.pnauth.dialog.AuthDialogFormFactory
import ru.privatenull.pnauth.dialog.DialogHandle
import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.dialog.CustomActionTransport
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageComponents
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.security.ClickCaptchaService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Bungee lifecycle bridge; mirrors the working Java implementation exactly. */
class BungeeDialogListener internal constructor(
    private val plugin: PnAuthBungeePlugin,
    private val auth: AuthApi,
    private val commands: AuthCommandService,
    private val messages: AuthMessages,
    private val settings: FeatureSettings,
    private val processingTitle: ProcessingTitleSettings,
    private val maxPasswordLength: Int,
    private val proxySettings: ProxySettings,
    private val platform: Platform,
    @Suppress("UNUSED_PARAMETER") commandRegistry: ru.privatenull.pnauth.command.CommandRegistry
) : Listener {

    private val dialogs: PlayerDialogs = platform.dialogs()
    private val captcha = ClickCaptchaService(settings.captcha)
    private val pending: MutableMap<UUID, ScheduledTask> = ConcurrentHashMap()
    private val scheduleGenerations: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val activeDialogs: MutableMap<UUID, DialogHandle> = ConcurrentHashMap()
    private val processingAnimations: MutableMap<UUID, ScheduledTask> = ConcurrentHashMap()
    private val uiCommand = UiCommand()
    private val actionRegistration: AutoCloseable? = (dialogs as? CustomActionTransport)?.onAction(
        "pnauth:open_dialog"
    ) { playerId, _ ->
        plugin.proxy.scheduler.runAsync(plugin) {
            val player = plugin.proxy.getPlayer(playerId) ?: return@runAsync
            if (!auth.isAuthenticated(playerId)) {
                val status = auth.status(playerId)
                if (status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED) {
                    show(player, status == AuthStatus.UNREGISTERED)
                }
            }
        }
    }

    init {
        plugin.proxy.pluginManager.registerCommand(plugin, uiCommand)
    }

    @EventHandler
    fun onServerConnected(event: ServerConnectedEvent) {
        val player = event.player
        if (!proxySettings.isAuthServer(event.server.info.name)
            || auth.isAuthenticated(player.uniqueId)
        ) {
            clearNativeDialog(player.uniqueId)
        } else {
            scheduleWhenLoaded(player)
        }
    }

    @EventHandler
    fun onPostLogin(event: PostLoginEvent) {
        scheduleWhenLoaded(event.player)
    }

    @EventHandler
    fun onDisconnect(event: PlayerDisconnectEvent) {
        cancel(event.player.uniqueId)
        scheduleGenerations.remove(event.player.uniqueId)
        captcha.clear(event.player.uniqueId)
        clearNativeDialog(event.player.uniqueId)
        stopProcessingTitle(event.player.uniqueId)
    }

    fun allowAuthenticationCommand(player: ProxiedPlayer): Boolean {
        return !requiresCaptcha(player)
    }

    fun requestCaptcha(player: ProxiedPlayer) {
        if (requiresCaptcha(player)) sendCaptcha(player)
    }

    fun close() {
        pending.keys.forEach { cancel(it) }
        activeDialogs.values.forEach { it.close() }
        activeDialogs.clear()
        processingAnimations.values.forEach { it.cancel() }
        processingAnimations.clear()
        captcha.clearAll()
        actionRegistration?.close()
        plugin.proxy.pluginManager.unregisterCommand(uiCommand)
    }

    private fun execute(player: ProxiedPlayer, command: String, args: List<String>) {
        clearNativeDialog(player.uniqueId)
        showProcessingTitle(player)
        commands.execute(
            AuthCommandRequest(player.uniqueId, player.name, command, args) { player.hasPermission(it) }
        ).whenComplete { result, error ->
            stopProcessingTitle(player.uniqueId)
            if (error != null) {
                if (player.isConnected) sendDialogError(player, messages.text("operation-error"))
                return@whenComplete
            }
            if (auth.isAuthenticated(player.uniqueId)) {
                player.sendMessage(*BungeeMessages.components(messages.text("auth.success"), messages.format))
            } else if (player.isConnected) {
                val error = if (result.isNullOrEmpty()) messages.text("operation-error") else result[0]
                sendDialogError(player, error)
            }
        }
    }

    private fun show(player: ProxiedPlayer, register: Boolean, notice: Component? = null) {
        val command = if (register) "register" else "login"
        val pnPlayer = platform.player(player.uniqueId)
            .orElseThrow { IllegalStateException("Player left before the dialog was shown") }
        val content = AuthDialogFormFactory.Content(
            dialogComponent(if (register) "dialog.register.title" else "dialog.login.title"),
            dialogComponent(if (register) "dialog.register.description" else "dialog.login.description"),
            notice,
            dialogComponent(if (register) "dialog.register.password" else "dialog.login.password"),
            if (register) dialogComponent("dialog.register.repeat") else null,
            dialogComponent(if (register) "dialog.register.button" else "dialog.login.button")
        )
        val form = AuthDialogFormFactory.create(
            if (register) AuthDialogFormFactory.Mode.REGISTER else AuthDialogFormFactory.Mode.LOGIN,
            settings.repeatPasswordWhenRegister, maxPasswordLength, content,
            { credentials ->
                activeDialogs.remove(player.uniqueId)
                if (!isOnAuthServer(player) || auth.isAuthenticated(player.uniqueId)) return@create
                if (!captcha.verified(player.uniqueId)) {
                    sendCaptcha(player)
                    return@create
                }
                execute(
                    player, command,
                    if (command == "login") listOf(credentials.password)
                    else listOf(credentials.password, credentials.confirmation)
                )
            },
            { sendDialogError(player, messages.text("operation-error")) }
        )
        val handle = dialogs.show(pnPlayer, form)
        val previous = activeDialogs.put(player.uniqueId, handle)
        previous?.close()
    }

    private fun dialogComponent(key: String): Component {
        return BungeeMessages.adventureComponent(messages.text(key), messages.format)
    }

    private fun scheduleWhenLoaded(player: ProxiedPlayer) {
        cancel(player.uniqueId)
        val generation = scheduleGenerations.merge(player.uniqueId, 1L, Long::plus)!!
        clearNativeDialog(player.uniqueId)
        val deadline = System.currentTimeMillis() + 30_000L
        val task = plugin.proxy.scheduler.schedule(plugin, {
            if (scheduleGenerations[player.uniqueId] != generation) return@schedule
            if (!player.isConnected) {
                cancel(player.uniqueId)
                return@schedule
            }
            val status = auth.status(player.uniqueId)
            if (status == AuthStatus.NOT_LOADED || player.server == null
                || !proxySettings.isAuthServer(player.server.info.name)
            ) {
                if (System.currentTimeMillis() >= deadline) {
                    cancel(player.uniqueId)
                    clearNativeDialog(player.uniqueId)
                }
                return@schedule
            }
            cancel(player.uniqueId)
            if (status == AuthStatus.AUTHENTICATED) return@schedule
            val protocol = player.pendingConnection.version
            val passwordStage = status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED
            val shouldShowDialog = passwordStage && auth.shouldUseDialog(player.uniqueId, protocol, true)
            if (!shouldShowDialog) {
                sendCommandFallback(player, status, protocol, passwordStage)
                return@schedule
            }
            if (!captcha.verified(player.uniqueId)) {
                sendCaptcha(player)
                return@schedule
            }
            try {
                show(player, status == AuthStatus.UNREGISTERED)
            } catch (exception: RuntimeException) {
                plugin.logger.warning(
                    "Could not show Bungee dialog for ${player.name}; falling back to commands: ${exception.message}"
                )
                sendCommandFallback(player, status, protocol, true)
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
        pending[player.uniqueId] = task
    }

    private fun cancel(playerId: UUID) {
        scheduleGenerations.merge(playerId, 1L, Long::plus)
        pending.remove(playerId)?.cancel()
    }

    private fun clearNativeDialog(playerId: UUID) {
        activeDialogs.remove(playerId)?.close()
    }

    private fun requiresCaptcha(player: ProxiedPlayer): Boolean {
        if (!isOnAuthServer(player) || captcha.verified(player.uniqueId)
            || auth.isAuthenticated(player.uniqueId)
        ) return false
        val status = auth.status(player.uniqueId)
        return status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED
    }

    private fun isOnAuthServer(player: ProxiedPlayer): Boolean {
        return player.isConnected && player.server != null
            && proxySettings.isAuthServer(player.server.info.name)
    }

    private fun sendCommandFallback(
        player: ProxiedPlayer,
        status: AuthStatus,
        protocol: Int,
        passwordStage: Boolean
    ) {
        if (!passwordStage || auth.shouldUseCommandFallback(player.uniqueId, protocol, true)) {
            player.sendMessage(*BungeeMessages.components(messages.prompt(status), messages.format))
        }
    }

    private fun sendCaptcha(player: ProxiedPlayer) {
        val challenge = captcha.issue(player.uniqueId)
        player.sendMessage(*BungeeMessages.components(
            messages.text("captcha.prompt", mapOf("answer" to challenge.answer)), messages.format
        ))
        val options = mutableListOf<BaseComponent>()
        for (option in challenge.options) {
            val button = BungeeMessages.components(
                messages.text("captcha.option", mapOf("value" to option.label)), messages.format
            )
            for (part in button) {
                part.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/_pnauthui captcha ${option.token}")
                options.add(part)
            }
            options.add(net.md_5.bungee.api.chat.TextComponent(" "))
        }
        player.sendMessage(*options.toTypedArray())
    }

    private fun sendDialogError(player: ProxiedPlayer, error: String) {
        clearNativeDialog(player.uniqueId)

        val titleComp = BungeeMessages.component(messages.text("title.error"), messages.format)
        val subtitleComp = BungeeMessages.component(
            messages.text("subtitle.error", mapOf("error" to visibleText(error))), messages.format
        )
        val titleObj = plugin.proxy.createTitle()
            .title(titleComp)
            .subTitle(subtitleComp)
            .fadeIn(5)
            .stay(50)
            .fadeOut(10)
        player.sendTitle(titleObj)

        if (settings.dialogs.reopenOnFailure && isOnAuthServer(player)) {
            val status = auth.status(player.uniqueId)
            if (status == AuthStatus.UNREGISTERED || status == AuthStatus.UNAUTHENTICATED) {
                show(player, status == AuthStatus.UNREGISTERED, BungeeMessages.adventureComponent(error, messages.format))
                return
            }
        }

        player.sendMessage(*BungeeMessages.components(
            messages.text("dialog.error", mapOf("error" to visibleText(error))), messages.format
        ))
    }

    private fun visibleText(value: String): String = MessageComponents.serializePlain(
        MessageComponents.deserialize(value, messages.format)
    )

    private fun showProcessingTitle(player: ProxiedPlayer) {
        stopProcessingTitle(player.uniqueId)
        if (!processingTitle.enabled) return
        val frames = ru.privatenull.pnauth.display.ProcessingTitleAnimation.generateFrames(
            messages.text("title.processing"),
            processingTitle.animation
        )
        if (frames.isEmpty()) return
        val subtitleComp = BungeeMessages.component(messages.text("subtitle.processing"), messages.format)
        val frame = java.util.concurrent.atomic.AtomicInteger()
        val intervalMillis = processingTitle.timings.frameInterval.toMillis().coerceAtLeast(50L)
        val stayTicks = ((processingTitle.timings.stay.toMillis() + 49L) / 50L).toInt().coerceAtLeast(20)
        val task = plugin.proxy.scheduler.schedule(plugin, {
            if (!player.isConnected || auth.isAuthenticated(player.uniqueId)) {
                stopProcessingTitle(player.uniqueId)
                return@schedule
            }
            val titleComp = BungeeMessages.component(frames[frame.getAndIncrement() % frames.size], messages.format)
            val titleObj = plugin.proxy.createTitle()
                .title(titleComp)
                .subTitle(subtitleComp)
                .fadeIn(0)
                .stay(stayTicks)
                .fadeOut(0)
            player.sendTitle(titleObj)
        }, 0L, intervalMillis, TimeUnit.MILLISECONDS)
        processingAnimations[player.uniqueId] = task
    }

    private fun stopProcessingTitle(playerId: UUID) {
        processingAnimations.remove(playerId)?.cancel()
    }

    private inner class UiCommand : Command("_pnauthui") {
        override fun execute(sender: CommandSender, args: Array<String>) {
            if (sender !is ProxiedPlayer) return
            if (!auth.isAuthenticated(sender.uniqueId)) handleUi(sender, args)
        }
    }

    private fun handleUi(player: ProxiedPlayer, args: Array<String>) {
        if (!isOnAuthServer(player)) return
        if (args.size == 1 && args[0].equals("open", ignoreCase = true)) {
            scheduleWhenLoaded(player)
            return
        }
        if (args.size != 2 || !args[0].equals("captcha", ignoreCase = true)) return
        val result = captcha.verify(player.uniqueId, args[1])
        if (result == ClickCaptchaService.Result.SUCCESS) {
            player.sendMessage(*BungeeMessages.components(messages.text("captcha.success"), messages.format))
            scheduleWhenLoaded(player)
            return
        }
        val key = when (result) {
            ClickCaptchaService.Result.INVALID -> "captcha.invalid"
            ClickCaptchaService.Result.EXPIRED -> "captcha.expired"
            ClickCaptchaService.Result.LOCKED -> "captcha.locked"
            else -> "captcha.invalid"
        }
        player.sendMessage(*BungeeMessages.components(messages.text(key), messages.format))
        if (result != ClickCaptchaService.Result.INVALID) sendRetryCaptcha(player)
    }

    private fun sendRetryCaptcha(player: ProxiedPlayer) {
        val retry = BungeeMessages.components(messages.text("captcha.retry"), messages.format)
        for (part in retry) {
            part.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/_pnauthui open")
        }
        player.sendMessage(*retry)
    }
}
