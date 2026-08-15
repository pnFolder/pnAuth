package ru.privatenull.pnauth.transport.packetevents

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData
import com.github.retrooper.packetevents.protocol.dialog.NoticeDialog
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData
import com.github.retrooper.packetevents.protocol.nbt.NBT
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTList
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber
import com.github.retrooper.packetevents.protocol.nbt.NBTString
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog
import net.kyori.adventure.text.Component
import ru.privatenull.pnauth.dialog.DialogAction
import ru.privatenull.pnauth.dialog.DialogButton
import ru.privatenull.pnauth.dialog.DialogHandle
import ru.privatenull.pnauth.dialog.DialogLayout
import ru.privatenull.pnauth.dialog.DialogResponse
import ru.privatenull.pnauth.dialog.DialogSubscription
import ru.privatenull.pnauth.dialog.DialogType
import ru.privatenull.pnauth.dialog.PlayerDialog
import ru.privatenull.pnauth.dialog.PlayerDialogs
import ru.privatenull.pnauth.platform.PlayerResourceKey
import ru.privatenull.pnauth.platform.PnPlayer
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import java.util.function.Function

/** Complete native dialog transport shared by BungeeCord and Velocity. */
class PacketEventsPlayerDialogs @JvmOverloads constructor(
    private val nativePlayer: Function<UUID, Any?>,
    private val diagnostics: Consumer<String> = Consumer { }
) : PlayerDialogs, AutoCloseable {

    private val packets = PacketEvents.getAPI()
    private val handles: ConcurrentMap<PlayerResourceKey, Handle> = ConcurrentHashMap()
    private val actions: ConcurrentMap<PlayerResourceKey, Handle> = ConcurrentHashMap()
    private val listener: PacketListenerCommon

    init {
        if (!packets.isLoaded || packets.isTerminated) {
            throw IllegalStateException("PacketEvents must be loaded before creating dialog transport")
        }
        listener = packets.eventManager.registerListener(ResponseListener())
    }

    override fun supported(player: PnPlayer): Boolean {
        val nativeValue = nativePlayer.apply(player.uniqueId())
        if (nativeValue == null) return false
        val ver = packets.playerManager.getClientVersion(nativeValue)
        // UNKNOWN means PacketEvents doesn't recognise the version — future/snapshot clients.
        // They still receive dialog packets, so treat them as supported.
        return ver == ClientVersion.UNKNOWN
            || ver.isNewerThanOrEquals(ClientVersion.V_1_21_6)
    }

    override fun show(player: PnPlayer, dialog: PlayerDialog): DialogHandle {
        if (!supported(player)) throw UnsupportedOperationException("Native dialogs require client 1.21.4+")
        val key = PlayerResourceKey(player.uniqueId(), dialog.id)
        return handles.compute(key) { _, current ->
            if (current == null || !current.active()) Handle(key, dialog)
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
        if (!packets.isTerminated) packets.eventManager.unregisterListener(listener)
    }

    private inner class Handle(
        private val key: PlayerResourceKey,
        dialog: PlayerDialog
    ) : DialogHandle {
        private val firstResponse = CompletableFuture<DialogResponse>()
        private val listeners = CopyOnWriteArrayList<Consumer<DialogResponse>>()
        @Volatile private var currentDialog: PlayerDialog = dialog
        @Volatile private var isActive = true

        init {
            replace(dialog)
        }

        override fun playerId(): UUID = key.playerId
        override fun dialogId(): String = key.name
        override fun active(): Boolean = isActive
        override fun response(): CompletionStage<DialogResponse> = firstResponse
        override fun onResponse(consumer: Consumer<DialogResponse>): DialogSubscription {
            listeners.add(consumer)
            return DialogSubscription { listeners.remove(consumer) }
        }

        @Synchronized
        override fun replace(replacement: PlayerDialog) {
            if (!isActive) throw IllegalStateException("Dialog handle is closed")
            unregisterActions()
            currentDialog = replacement
            registerActions(replacement)
            val player = nativePlayer.apply(key.playerId)
            if (player != null) {
                // Do not override PacketEvents' negotiated protocol. The Java implementation
                // sends this wrapper unchanged; forcing 1.21.4 makes modern clients reject it.
                sendSafely(player, WrapperPlayServerShowDialog(PacketEventsDialogMapper.map(replacement)))
            }
        }

        @Synchronized
        override fun close() {
            if (!isActive) return
            isActive = false
            handles.remove(key, this)
            unregisterActions()
            val player = nativePlayer.apply(key.playerId)
            if (player != null) {
                // Disconnect releases PacketEvents' user/channel first on some Bungee builds.
                // sendSafely absorbs that expected race instead of failing the disconnect event.
                sendSafely(player, WrapperPlayServerClearDialog())
            }
            publish(DialogResponse("", emptyMap(), true))
        }

        fun accept(action: String, values: Map<String, Any>) {
            if (!isActive) return
            if (currentDialog.layout.afterAction == DialogLayout.AfterAction.CLOSE) {
                isActive = false
                handles.remove(key, this)
                unregisterActions()
            }
            publish(DialogResponse(action, values, false))
        }

        private fun publish(response: DialogResponse) {
            firstResponse.complete(response)
            listeners.forEach { consumer -> consumer.accept(response) }
        }

        private fun registerActions(value: PlayerDialog) {
            for (button in buttons(value.type)) {
                when (val action = button.action) {
                    is DialogAction.DynamicCustom -> {
                        actions[PlayerResourceKey(key.playerId, action.id)] = this
                    }
                    is DialogAction.Static -> {
                        if (action.type.replace("minecraft:", "") == "custom") {
                            actions[PlayerResourceKey(key.playerId, action.payload["id"].toString())] = this
                        }
                    }
                    else -> {}
                }
            }
        }

        private fun unregisterActions() {
            actions.entries.removeIf { it.value == this }
        }
    }

    private fun testDialog(player: Any) {
        try {
            val dialog = NoticeDialog(
                CommonDialogData(
                    Component.text("Test Dialog"),
                    PlainMessage(
                        Component.text("Hello from PacketEvents!"),
                        300
                    ).contents,
                    false,
                    false,
                    com.github.retrooper.packetevents.protocol.dialog.DialogAction.NONE,
                    emptyList(),
                    emptyList()
                ),
                ActionButton(
                    CommonButtonData(
                        Component.text("OK"),
                        null,
                        100
                    ),
                    null
                )
            )

            packets.playerManager.sendPacket(
                player,
                WrapperPlayServerShowDialog(dialog)
            )

            diagnostics.accept("[pnAuth] testDialog packet sent successfully")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun sendSafely(player: Any, packet: Any) {
        try {
            packets.playerManager.sendPacket(player, packet)
        } catch (exception: Throwable) {
            try {
                val user = packets.protocolManager.getUser(player)
                if (user != null && packet is com.github.retrooper.packetevents.wrapper.PacketWrapper<*>) {
                    user.sendPacket(packet)
                    return
                }
            } catch (_: Throwable) {
                // Disconnect may release the PacketEvents channel before the Bungee event fires.
            }
        }
    }

    private inner class ResponseListener : PacketListenerAbstract() {
        override fun onPacketReceive(event: PacketReceiveEvent) {
            if (event.packetType != PacketType.Play.Client.CUSTOM_CLICK_ACTION) return
            val actionId: String
            val payload: Map<String, Any>
            try {
                val packet = WrapperPlayClientCustomClickAction(event)
                actionId = packet.id.toString()
                if (!actionId.startsWith("pnauth:")) return
                payload = values(packet.payload)
            } catch (ignored: RuntimeException) {
                // Only malformed client-controlled packet decoding is intentionally ignored.
                return
            }
            try {
                var playerId = platformPlayerId(event.getPlayer())
                if (playerId == null) playerId = event.user.uuid
                var handle = if (playerId == null) null else actions[PlayerResourceKey(playerId, actionId)]
                if (handle == null) handle = actionForConnection(actionId, event)
                if (handle == null) {
                    diagnostics.accept("[dialogs] Rejected an unmatched pnAuth action $actionId")
                    return
                }
                event.isCancelled = true
                handle.accept(actionId, payload)
            } catch (ignored: RuntimeException) {
                // DialogForm converts application callback failures into its configured error response.
            }
        }
    }

    private fun actionForConnection(actionId: String, event: PacketReceiveEvent): Handle? {
        for ((key, handle) in actions) {
            if (key.name != actionId) continue
            val expectedPlayer = nativePlayer.apply(key.playerId) ?: continue
            if (expectedPlayer == event.getPlayer()) return handle
            try {
                if (packets.protocolManager.getUser(expectedPlayer) == event.user) {
                    return handle
                }
            } catch (ignored: RuntimeException) {
                // The player may be between Bungee login states; UUID/direct identity checks remain available.
            }
        }
        return null
    }

    companion object {
        private fun platformProtocolVersion(player: Any?): Int {
            if (player == null) return 0
            return try {
                val pendingConnection = player.javaClass.getMethod("getPendingConnection").invoke(player)
                val version = pendingConnection.javaClass.getMethod("getVersion").invoke(pendingConnection)
                if (version is Number) version.toInt() else 0
            } catch (ignored: Exception) {
                try {
                    val protocolVersion = player.javaClass.getMethod("getProtocolVersion").invoke(player)
                    if (protocolVersion is Number) protocolVersion.toInt() else 0
                } catch (ignored2: Exception) {
                    0
                }
            }
        }

        private fun platformPlayerId(player: Any?): UUID? {
            if (player == null) return null
            return try {
                val value = player.javaClass.getMethod("getUniqueId").invoke(player)
                if (value is UUID) value else null
            } catch (ignored: ReflectiveOperationException) {
                null
            }
        }

        private fun values(payload: NBT?): Map<String, Any> {
            if (payload !is NBTCompound) return emptyMap()
            val result = LinkedHashMap<String, Any>()
            payload.tags.forEach { (key, value) -> result[key] = value(value) }
            return java.util.Map.copyOf(result)
        }

        private fun value(value: NBT): Any {
            return when (value) {
                is NBTString -> value.value
                is NBTNumber -> value.asNumber
                is NBTCompound -> values(value)
                is NBTList<*> -> value.tags.map { value(it as NBT) }
                else -> value.toString()
            }
        }

        private fun buttons(type: DialogType): List<DialogButton> {
            return when (type) {
                is DialogType.Notice -> listOf(type.action)
                is DialogType.Confirmation -> listOf(type.yes, type.no)
                is DialogType.MultiAction -> {
                    val result = ArrayList(type.actions)
                    if (type.exitAction != null) result.add(type.exitAction!!)
                    result
                }
                is DialogType.ServerLinks -> if (type.exitAction != null) listOf(type.exitAction!!) else emptyList()
                is DialogType.DialogList -> if (type.exitAction != null) listOf(type.exitAction!!) else emptyList()
            }
        }
    }
}
