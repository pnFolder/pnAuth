package ru.privatenull.pnauth.velocity.dialog

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData
import com.github.retrooper.packetevents.protocol.dialog.Dialog
import com.github.retrooper.packetevents.protocol.dialog.DialogAction
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction
import com.github.retrooper.packetevents.protocol.dialog.body.DialogBody
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData
import com.github.retrooper.packetevents.protocol.dialog.input.Input
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog
import com.velocitypowered.api.proxy.Player
import ru.privatenull.pnauth.dialog.AuthDialogDimensions
import java.util.regex.Pattern

class PacketEventsVelocityDialogService(
    private val submissions: VelocityDialogService.SubmissionHandler
) : VelocityDialogService {

    private val packets: PacketEventsAPI<*> = PacketEvents.getAPI()
    private val listener: PacketListenerCommon

    init {
        if (!packets.isLoaded || packets.isTerminated) {
            throw IllegalStateException("PacketEvents API is not ready")
        }
        listener = packets.eventManager.registerListener(ResponseListener())
    }

    override fun available(): Boolean {
        return !packets.isTerminated
    }

    override fun show(player: Player, form: VelocityDialogService.DialogForm) {
        if (!ACTION_ID.matcher(form.actionId).matches()) {
            throw IllegalArgumentException("Unsupported dialog action: " + form.actionId)
        }
        val inputs = ArrayList<Input>(form.fields.size)
        for (field in form.fields) {
            inputs.add(
                Input(
                    field.key, TextInputControl(
                        AuthDialogDimensions.FIELD_WIDTH, field.label, true, "", field.maxLength, null
                    )
                )
            )
        }
        val body: List<DialogBody> = if (form.notice == null) emptyList()
        else listOf(
            PlainMessageDialogBody(
                PlainMessage(
                    form.notice, AuthDialogDimensions.BODY_WIDTH
                )
            )
        )
        val common = CommonDialogData(
            form.title, null, false, false, DialogAction.NONE, body, inputs
        )
        val submit = ActionButton(
            CommonButtonData(form.submitLabel, null, AuthDialogDimensions.SUBMIT_BUTTON_WIDTH),
            DynamicCustomAction(ResourceLocation(form.actionId), null)
        )
        val dialog: Dialog = MultiActionDialog(common, listOf(submit), null, 1)
        packets.playerManager.sendPacket(player, WrapperPlayServerShowDialog(dialog))
    }

    override fun clear(player: Player) {
        if (packets.isTerminated || packets.protocolManager.getUser(player) == null) return
        packets.playerManager.sendPacket(player, WrapperPlayServerClearDialog())
    }

    override fun close() {
        if (!packets.isTerminated) {
            packets.eventManager.unregisterListener(listener)
        }
    }

    private inner class ResponseListener : PacketListenerAbstract() {
        override fun onPacketReceive(event: PacketReceiveEvent) {
            if (event.packetType != PacketType.Play.Client.CUSTOM_CLICK_ACTION) return
            val packet = WrapperPlayClientCustomClickAction(event)
            val action = packet.id.toString()
            if (!ACTION_ID.matcher(action).matches()) return
            event.isCancelled = true
            val player: Player = event.getPlayer() ?: return
            val payload = packet.payload
            val compound = if (payload is NBTCompound) payload else NBTCompound()
            val values = HashMap<String, String>()
            values["password"] = compound.getStringTagValueOrDefault("password", "")
            values["confirmation"] = compound.getStringTagValueOrDefault("confirmation", "")
            submissions.submit(player, action, java.util.Map.copyOf(values))
        }
    }

    companion object {
        private val ACTION_ID: Pattern = Pattern.compile("pnauth:(?:login|register)-[0-9a-f]{32}")
    }
}
