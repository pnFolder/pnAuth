package ru.privatenull.pnauth.velocity.dialog;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.dialog.DialogAction;
import com.github.retrooper.packetevents.protocol.dialog.MultiActionDialog;
import com.github.retrooper.packetevents.protocol.dialog.action.DynamicCustomAction;
import com.github.retrooper.packetevents.protocol.dialog.body.DialogBody;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.button.ActionButton;
import com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData;
import com.github.retrooper.packetevents.protocol.dialog.input.Input;
import com.github.retrooper.packetevents.protocol.dialog.input.TextInputControl;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import com.velocitypowered.api.proxy.Player;
import ru.privatenull.pnauth.dialog.AuthDialogDimensions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** @deprecated Legacy direct adapter; shared PacketEventsPlayerDialogs now owns dialog transport. */
@Deprecated(forRemoval = false)
public final class PacketEventsVelocityDialogService implements VelocityDialogService {
    /** Only server-issued, tokenized IDs reach the coordinator; it performs the final exact authorization. */
    private static final Pattern ACTION_ID = Pattern.compile("pnauth:(?:login|register)-[0-9a-f]{32}");
    private final PacketEventsAPI<?> packets;
    private final SubmissionHandler submissions;
    private final PacketListenerCommon listener;

    public PacketEventsVelocityDialogService(SubmissionHandler submissions) {
        packets = PacketEvents.getAPI();
        if (!packets.isLoaded() || packets.isTerminated()) {
            throw new IllegalStateException("PacketEvents API is not ready");
        }
        this.submissions = submissions;
        listener = packets.getEventManager().registerListener(new ResponseListener());
    }

    public boolean available() {
        return !packets.isTerminated();
    }

    public void show(Player player, DialogForm form) {
        if (!ACTION_ID.matcher(form.actionId()).matches()) {
            throw new IllegalArgumentException("Unsupported dialog action: " + form.actionId());
        }
        List<Input> inputs = new ArrayList<>(form.fields().size());
        for (TextField field : form.fields()) {
            inputs.add(new Input(field.key(), new TextInputControl(
                    AuthDialogDimensions.FIELD_WIDTH, field.label(), true, "", field.maxLength(), null)));
        }
        List<DialogBody> body = form.notice() == null ? List.of()
                : List.of(new PlainMessageDialogBody(new PlainMessage(
                        form.notice(), AuthDialogDimensions.BODY_WIDTH)));
        CommonDialogData common = new CommonDialogData(
                form.title(), null, false, false, DialogAction.NONE, body, inputs);
        ActionButton submit = new ActionButton(
                new CommonButtonData(form.submitLabel(), null, AuthDialogDimensions.SUBMIT_BUTTON_WIDTH),
                new DynamicCustomAction(new ResourceLocation(form.actionId()), null));
        Dialog dialog = new MultiActionDialog(common, List.of(submit), null, 1);
        packets.getPlayerManager().sendPacket(player, new WrapperPlayServerShowDialog(dialog));
    }

    public void clear(Player player) {
        // Velocity removes the PacketEvents user before publishing DisconnectEvent.
        // Sending ClearDialog after that point dereferences a missing protocol user.
        if (packets.isTerminated() || packets.getProtocolManager().getUser(player) == null) return;
        packets.getPlayerManager().sendPacket(player, new WrapperPlayServerClearDialog());
    }

    public void close() {
        if (!packets.isTerminated()) packets.getEventManager().unregisterListener(listener);
    }

    private final class ResponseListener extends PacketListenerAbstract {
        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPacketType() != PacketType.Play.Client.CUSTOM_CLICK_ACTION) return;
            WrapperPlayClientCustomClickAction packet = new WrapperPlayClientCustomClickAction(event);
            String action = packet.getId().toString();
            if (!ACTION_ID.matcher(action).matches()) return;
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (player == null) return;
            NBT payload = packet.getPayload();
            NBTCompound compound = payload instanceof NBTCompound value ? value : new NBTCompound();
            Map<String, String> values = new HashMap<>();
            values.put("password", compound.getStringTagValueOrDefault("password", ""));
            values.put("confirmation", compound.getStringTagValueOrDefault("confirmation", ""));
            submissions.submit(player, action, Map.copyOf(values));
        }
    }
}
