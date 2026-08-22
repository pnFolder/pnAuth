package ru.privatenull.pnauth.paper

import com.fasterxml.jackson.databind.ObjectMapper
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import ru.privatenull.pnauth.dialog.DialogAction
import ru.privatenull.pnauth.dialog.DialogBody
import ru.privatenull.pnauth.dialog.DialogButton
import ru.privatenull.pnauth.dialog.DialogHandle
import ru.privatenull.pnauth.dialog.DialogInput
import ru.privatenull.pnauth.dialog.DialogLayout
import ru.privatenull.pnauth.dialog.DialogResponse
import ru.privatenull.pnauth.dialog.DialogSubscription
import ru.privatenull.pnauth.dialog.DialogType
import ru.privatenull.pnauth.dialog.PlayerDialog
import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.platform.Player
import ru.privatenull.pnauth.platform.PlayerResourceKey
import ru.privatenull.pnauth.platform.adapter.PlatformDialogAdapter
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/** Runtime adapter for the vanilla dialog protocol available since Minecraft 1.21.6. */
class PaperPlayerDialogs(private val plugin: Plugin) : PlatformDialogAdapter, AutoCloseable {

    private val nativeDialogsAvailable: Boolean = supportsNativeDialogs()
    private val handles: ConcurrentMap<PlayerResourceKey, NativeHandle> = ConcurrentHashMap()
    private val actions: ConcurrentMap<PlayerResourceKey, NativeHandle> = ConcurrentHashMap()

    init {
        if (nativeDialogsAvailable) registerResponseListener()
    }

    override fun supported(player: Player): Boolean {
        return nativeDialogsAvailable && player.connected()
    }

    override fun show(player: Player, dialog: PlayerDialog): DialogHandle {
        if (!supported(player)) {
            throw UnsupportedOperationException("Native dialogs with responses require Paper 1.21.7 or newer")
        }
        val key = PlayerResourceKey(player.uniqueId(), dialog.id)
        return handles.compute(key) { _, current ->
            if (current == null || !current.active()) NativeHandle(key, dialog)
            else {
                current.replace(dialog)
                current
            }
        }!!
    }

    override fun find(playerId: UUID, dialogId: String): Optional<DialogHandle> {
        return Optional.ofNullable(handles[PlayerResourceKey(playerId, dialogId)])
    }

    override fun close(playerId: UUID, dialogId: String): Boolean {
        val handle = handles[PlayerResourceKey(playerId, dialogId)] ?: return false
        handle.close()
        return true
    }

    override fun closeAll(playerId: UUID) {
        handles.entries.stream()
            .filter { it.key.playerId == playerId }
            .map { it.value }
            .toList()
            .forEach { it.close() }
    }

    override fun close() {
        handles.values.forEach { it.close() }
        handles.clear()
        actions.clear()
    }

    private inner class NativeHandle(
        private val key: PlayerResourceKey,
        dialog: PlayerDialog
    ) : DialogHandle {
        private val responseFuture = CompletableFuture<DialogResponse>()
        private val listeners = CopyOnWriteArrayList<Consumer<DialogResponse>>()
        @Volatile var currentDialog: PlayerDialog = dialog
        @Volatile private var isActive = true

        init {
            replace(dialog)
        }

        override fun playerId(): UUID = key.playerId
        override fun dialogId(): String = key.name
        override fun active(): Boolean = isActive
        override fun response(): CompletionStage<DialogResponse> = responseFuture
        override fun onResponse(listener: Consumer<DialogResponse>): DialogSubscription {
            listeners.add(Objects.requireNonNull(listener, "listener"))
            return DialogSubscription { listeners.remove(listener) }
        }

        @Synchronized
        override fun replace(replacement: PlayerDialog) {
            if (!isActive) throw IllegalStateException("Dialog handle is closed")
            unregisterActions()
            currentDialog = replacement
            registerActions(replacement)
            execute("dialog show " + key.playerId + " " + serialize(replacement))
        }

        @Synchronized
        override fun close() {
            if (!isActive) return
            isActive = false
            handles.remove(key, this)
            unregisterActions()
            execute("dialog clear " + key.playerId)
            publish(DialogResponse("", emptyMap(), true))
        }

        fun accept(action: String, values: Map<String, Any>) {
            if (!isActive) return
            publish(DialogResponse(action, values, false))
            if (currentDialog.layout.afterAction != DialogLayout.AfterAction.NONE) {
                isActive = false
                handles.remove(key, this)
                unregisterActions()
            }
        }

        private fun publish(value: DialogResponse) {
            responseFuture.complete(value)
            listeners.forEach { listener -> listener.accept(value) }
        }

        private fun registerActions(value: PlayerDialog) {
            buttons(value.type).forEach { button ->
                if (button != null && button.action is DialogAction.DynamicCustom) {
                    val custom = button.action as DialogAction.DynamicCustom
                    actions[PlayerResourceKey(key.playerId, custom.id)] = this
                }
            }
        }

        private fun unregisterActions() {
            actions.entries.removeIf { it.value == this }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerResponseListener() {
        try {
            val eventType = Class.forName("io.papermc.paper.event.player.PlayerCustomClickEvent") as Class<out Event>
            val listener = object : Listener {}
            Bukkit.getPluginManager().registerEvent(
                eventType, listener, EventPriority.NORMAL,
                { _, event -> receiveResponse(event) }, plugin, true
            )
        } catch (exception: ReflectiveOperationException) {
            throw IllegalStateException("Paper exposes dialogs but PlayerCustomClickEvent is unavailable", exception)
        }
    }

    private fun receiveResponse(event: Event) {
        try {
            val identifier = event.javaClass.getMethod("getIdentifier").invoke(event).toString()
            val connection = event.javaClass.getMethod("getCommonConnection").invoke(event)
            val getPlayer = connection.javaClass.getMethod("getPlayer")
            val player = getPlayer.invoke(connection)
            val playerId = player.javaClass.getMethod("getUniqueId").invoke(player) as UUID
            val handle = actions[PlayerResourceKey(playerId, identifier)] ?: return
            val view = event.javaClass.getMethod("getDialogResponseView").invoke(event)
            handle.accept(identifier, readValues(view, handle.currentDialog))
        } catch (exception: ReflectiveOperationException) {
            plugin.logger.warning("Could not decode a native dialog response: " + exception.message)
        }
    }

    private fun readValues(view: Any?, dialog: PlayerDialog): Map<String, Any> {
        val values = LinkedHashMap<String, Any>()
        if (view == null) return values
        for (input in dialog.layout.inputs) {
            val method = when (input) {
                is DialogInput.Text -> "getText"
                is DialogInput.Toggle -> "getBoolean"
                is DialogInput.NumberRange -> "getFloat"
                else -> "getText"
            }
            val result = view.javaClass.getMethod(method, String::class.java).invoke(view, input.id())
            if (result != null) {
                values[input.id()] = result
            }
        }
        return values
    }

    private fun execute(command: String) {
        Bukkit.getGlobalRegionScheduler().execute(plugin) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
        }
    }

    private fun serialize(dialog: PlayerDialog): String {
        return try {
            JSON.writeValueAsString(toDocument(dialog))
        } catch (exception: Exception) {
            throw IllegalArgumentException("Dialog cannot be serialized", exception)
        }
    }

    private fun toDocument(dialog: PlayerDialog): Map<String, Any> {
        val root = LinkedHashMap<String, Any>()
        root["title"] = component(dialog.layout.title)
        optional(root, "external_title", if (dialog.layout.externalTitle == null) null else component(dialog.layout.externalTitle!!))
        root["body"] = bodies(dialog.layout.body)
        root["inputs"] = inputs(dialog.layout.inputs)
        root["can_close_with_escape"] = dialog.layout.canCloseWithEscape
        root["pause"] = dialog.layout.pause
        root["after_action"] = lower(dialog.layout.afterAction.name)
        applyType(root, dialog.type)
        return root
    }

    private fun applyType(root: MutableMap<String, Any>, type: DialogType) {
        when (type) {
            is DialogType.Notice -> {
                root["type"] = "minecraft:notice"
                root["action"] = button(type.action)
            }
            is DialogType.Confirmation -> {
                root["type"] = "minecraft:confirmation"
                root["yes"] = button(type.yes)
                root["no"] = button(type.no)
            }
            is DialogType.MultiAction -> {
                root["type"] = "minecraft:multi_action"
                root["actions"] = buttonList(type.actions)
                optional(root, "exit_action", if (type.exitAction == null) null else button(type.exitAction!!))
                root["columns"] = type.columns
            }
            is DialogType.ServerLinks -> {
                root["type"] = "minecraft:server_links"
                root["columns"] = type.columns
                root["button_width"] = type.buttonWidth
                optional(root, "exit_action", if (type.exitAction == null) null else button(type.exitAction!!))
            }
            is DialogType.DialogList -> {
                root["type"] = "minecraft:dialog_list"
                root["columns"] = type.columns
                root["button_width"] = type.buttonWidth
                root["dialogs"] = if (type.dialogTag == null) {
                    type.dialogs.map { dialog ->
                        try {
                            toDocument(dialog)
                        } catch (exception: Exception) {
                            throw IllegalArgumentException(exception)
                        }
                    }
                } else {
                    "#" + type.dialogTag
                }
                optional(root, "exit_action", if (type.exitAction == null) null else button(type.exitAction!!))
            }
        }
    }

    private fun bodies(source: List<DialogBody>): List<Map<String, Any>> {
        val result = ArrayList<Map<String, Any>>()
        for (body in source) {
            val value = LinkedHashMap<String, Any>()
            when (body) {
                is DialogBody.PlainMessage -> {
                    value["type"] = "minecraft:plain_message"
                    value["contents"] = component(body.content)
                    value["width"] = body.width
                }
                is DialogBody.Item -> {
                    value["type"] = "minecraft:item"
                    value["item"] = body.itemStack
                    body.description?.let { desc ->
                        value["description"] = mapOf(
                            "contents" to component(desc),
                            "width" to body.descriptionWidth
                        )
                    }
                    value["show_decorations"] = body.showDecorations
                    value["show_tooltip"] = body.showTooltip
                    value["width"] = body.width
                    value["height"] = body.height
                }
            }
            result.add(value)
        }
        return result
    }

    private fun inputs(source: List<DialogInput>): List<Map<String, Any>> {
        val result = ArrayList<Map<String, Any>>()
        for (input in source) {
            val value = LinkedHashMap<String, Any>()
            value["key"] = input.id()
            value["label"] = component(input.label())
            when (input) {
                is DialogInput.Text -> {
                    value["type"] = "minecraft:text"
                    value["label_visible"] = input.labelVisible
                    value["initial"] = input.initialValue
                    value["max_length"] = input.maximumLength
                    value["width"] = input.width
                    if (input.multiline != null) {
                        val multiline = LinkedHashMap<String, Any>()
                        optional(multiline, "max_lines", input.multiline!!.maximumLines)
                        optional(multiline, "height", input.multiline!!.height)
                        value["multiline"] = multiline
                    }
                }
                is DialogInput.Toggle -> {
                    value["type"] = "minecraft:boolean"
                    value["initial"] = input.initialValue
                    optional(value, "on_true", input.onTrue)
                    optional(value, "on_false", input.onFalse)
                }
                is DialogInput.Choice -> {
                    value["type"] = "minecraft:single_option"
                    value["label_visible"] = input.labelVisible
                    value["width"] = input.width
                    val options = ArrayList<Map<String, Any>>()
                    for (option in input.options) {
                        options.add(
                            mapOf(
                                "id" to option.id,
                                "display" to component(option.display),
                                "initial" to option.initial
                            )
                        )
                    }
                    value["options"] = options
                }
                is DialogInput.NumberRange -> {
                    value["type"] = "minecraft:number_range"
                    optional(value, "label_format", input.labelFormat)
                    value["width"] = input.width
                    value["start"] = input.start
                    value["end"] = input.end
                    optional(value, "initial", input.initial)
                    optional(value, "step", input.step)
                }
            }
            result.add(value)
        }
        return result
    }

    private fun button(button: DialogButton): Map<String, Any> {
        val value = LinkedHashMap<String, Any>()
        value["label"] = component(button.label)
        button.tooltip?.let { value["tooltip"] = component(it) }
        value["width"] = button.width
        if (button.action !is DialogAction.None) value["action"] = action(button.action)
        return value
    }

    private fun buttonList(buttons: List<DialogButton>): List<Map<String, Any>> {
        val values = ArrayList<Map<String, Any>>()
        for (button in buttons) values.add(button(button))
        return values
    }

    private fun action(action: DialogAction): Map<String, Any> {
        val value = LinkedHashMap<String, Any>()
        when (action) {
            is DialogAction.Static -> {
                value["type"] = action.type
                value.putAll(action.payload)
            }
            is DialogAction.DynamicRunCommand -> {
                value["type"] = "minecraft:dynamic/run_command"
                value["template"] = action.template
            }
            is DialogAction.DynamicCustom -> {
                value["type"] = "minecraft:dynamic/custom"
                value["id"] = action.id
                if (action.additions.isNotEmpty()) value["additions"] = action.additions
            }
            else -> {}
        }
        return value
    }

    private fun component(component: net.kyori.adventure.text.Component): Any {
        return JSON.readTree(GsonComponentSerializer.gson().serialize(component))
    }

    private fun buttons(type: DialogType): List<DialogButton?> {
        return when (type) {
            is DialogType.Notice -> listOf(type.action)
            is DialogType.Confirmation -> listOf(type.yes, type.no)
            is DialogType.MultiAction -> {
                val result = ArrayList<DialogButton?>(type.actions)
                if (type.exitAction != null) result.add(type.exitAction)
                result
            }
            is DialogType.ServerLinks -> if (type.exitAction != null) listOf(type.exitAction) else emptyList()
            is DialogType.DialogList -> if (type.exitAction != null) listOf(type.exitAction) else emptyList()
        }
    }

    private fun supportsNativeDialogs(): Boolean {
        return supportsNativeDialogs(Bukkit.getMinecraftVersion())
    }

    companion object {
        private val JSON = ObjectMapper()

        internal fun supportsNativeDialogs(version: String): Boolean {
        val parts = version.split("\\.".toRegex()).toTypedArray()
        if (parts.size < 2) return false
        val major = parts[0].replace("\\D.*".toRegex(), "").toIntOrNull() ?: return false
        if (major >= 26) return true
        if (major != 1) return false
        val minor = parts[1].toInt()
        val patch = if (parts.size > 2) parts[2].replace("\\D.*".toRegex(), "").toInt() else 0
        return minor > 21 || (minor == 21 && patch >= 7)
        }

        private fun lower(value: String): String = value.lowercase(Locale.ROOT)
        private fun optional(target: MutableMap<String, Any>, key: String, value: Any?) {
            if (value != null) target[key] = value
        }
    }
}
