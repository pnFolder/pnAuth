package ru.privatenull.pnauth.transport.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCustomClickAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearDialog;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import ru.privatenull.pnauth.dialog.*;
import ru.privatenull.pnauth.platform.PlayerResourceKey;
import ru.privatenull.pnauth.platform.PnPlayer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/** Complete native dialog transport shared by BungeeCord and Velocity. */
public final class PacketEventsPlayerDialogs implements PlayerDialogs, AutoCloseable {
    private final com.github.retrooper.packetevents.PacketEventsAPI<?> packets = PacketEvents.getAPI();
    private final Function<UUID, Object> nativePlayer;
    private final ConcurrentMap<PlayerResourceKey, Handle> handles = new ConcurrentHashMap<>();
    private final ConcurrentMap<PlayerResourceKey, Handle> actions = new ConcurrentHashMap<>();
    private final PacketListenerCommon listener;

    public PacketEventsPlayerDialogs(Function<UUID, Object> nativePlayer) {
        this.nativePlayer = nativePlayer;
        if (!packets.isLoaded() || packets.isTerminated()) {
            throw new IllegalStateException("PacketEvents must be loaded before creating dialog transport");
        }
        listener = packets.getEventManager().registerListener(new ResponseListener());
    }

    @Override public boolean supported(PnPlayer player) {
        Object nativeValue = nativePlayer.apply(player.uniqueId());
        return nativeValue != null && packets.getPlayerManager().getClientVersion(nativeValue)
                .isNewerThanOrEquals(ClientVersion.V_1_21_6);
    }

    @Override public DialogHandle show(PnPlayer player, PlayerDialog dialog) {
        if (!supported(player)) throw new UnsupportedOperationException("Native dialogs require client 1.21.6+");
        PlayerResourceKey key = new PlayerResourceKey(player.uniqueId(), dialog.id());
        return handles.compute(key, (ignored, current) -> {
            if (current == null || !current.active()) return new Handle(key, dialog);
            current.replace(dialog);
            return current;
        });
    }

    @Override public Optional<DialogHandle> find(UUID playerId, String dialogId) {
        return Optional.ofNullable(handles.get(new PlayerResourceKey(playerId, dialogId)));
    }

    @Override public boolean close(UUID playerId, String dialogId) {
        Handle handle = handles.get(new PlayerResourceKey(playerId, dialogId));
        if (handle == null) return false;
        handle.close();
        return true;
    }

    @Override public void closeAll(UUID playerId) {
        handles.entrySet().stream().filter(entry -> entry.getKey().playerId.equals(playerId))
                .map(Map.Entry::getValue).toList().forEach(Handle::close);
    }

    @Override public void close() {
        handles.values().forEach(Handle::close);
        handles.clear(); actions.clear();
        if (!packets.isTerminated()) packets.getEventManager().unregisterListener(listener);
    }

    private final class Handle implements DialogHandle {
        private final PlayerResourceKey key;
        private final CompletableFuture<DialogResponse> firstResponse = new CompletableFuture<>();
        private final CopyOnWriteArrayList<Consumer<DialogResponse>> listeners = new CopyOnWriteArrayList<>();
        private volatile PlayerDialog dialog;
        private volatile boolean active = true;

        private Handle(PlayerResourceKey key, PlayerDialog dialog) { this.key = key; replace(dialog); }
        @Override public UUID playerId() { return key.playerId; }
        @Override public String dialogId() { return key.name; }
        @Override public boolean active() { return active; }
        @Override public CompletionStage<DialogResponse> response() { return firstResponse; }
        @Override public DialogSubscription onResponse(Consumer<DialogResponse> consumer) {
            listeners.add(Objects.requireNonNull(consumer, "listener"));
            return () -> listeners.remove(consumer);
        }

        @Override public synchronized void replace(PlayerDialog replacement) {
            if (!active) throw new IllegalStateException("Dialog handle is closed");
            unregisterActions(); dialog = replacement; registerActions(replacement);
            Object player = nativePlayer.apply(key.playerId);
            if (player != null) packets.getPlayerManager().sendPacket(player,
                    new WrapperPlayServerShowDialog(PacketEventsDialogMapper.map(replacement)));
        }

        @Override public synchronized void close() {
            if (!active) return;
            active = false; handles.remove(key, this); unregisterActions();
            Object player = nativePlayer.apply(key.playerId);
            if (player != null) packets.getPlayerManager().sendPacket(player, new WrapperPlayServerClearDialog());
            publish(new DialogResponse("", Map.of(), true));
        }

        private void accept(String action, Map<String, Object> values) {
            if (!active) return;
            if (dialog.layout().afterAction() == DialogLayout.AfterAction.CLOSE) {
                active = false; handles.remove(key, this); unregisterActions();
            }
            publish(new DialogResponse(action, values, false));
        }

        private void publish(DialogResponse response) {
            firstResponse.complete(response);
            listeners.forEach(consumer -> consumer.accept(response));
        }

        private void registerActions(PlayerDialog value) {
            for (DialogButton button : buttons(value.type())) {
                if (button.action() instanceof DialogAction.DynamicCustom custom) {
                    actions.put(new PlayerResourceKey(key.playerId, custom.id()), this);
                } else if (button.action() instanceof DialogAction.Static fixed
                        && fixed.type().replace("minecraft:", "").equals("custom")) {
                    actions.put(new PlayerResourceKey(key.playerId, String.valueOf(fixed.payload().get("id"))), this);
                }
            }
        }

        private void unregisterActions() { actions.entrySet().removeIf(entry -> entry.getValue() == this); }
    }

    private final class ResponseListener extends PacketListenerAbstract {
        @Override public void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPacketType() != PacketType.Play.Client.CUSTOM_CLICK_ACTION) return;
            try {
                WrapperPlayClientCustomClickAction packet = new WrapperPlayClientCustomClickAction(event);
                String actionId = packet.getId().toString();
                if (!actionId.startsWith("pnauth:")) return;
                UUID playerId = platformPlayerId(event.getPlayer());
                if (playerId == null && event.getUser() != null) playerId = event.getUser().getUUID();
                Handle handle = playerId == null ? null : actions.get(new PlayerResourceKey(playerId, actionId));
                if (handle == null) handle = actionForConnection(actionId, event);
                if (handle == null) return;
                event.setCancelled(true);
                Map<String, Object> payload = values(packet.getPayload());
                handle.accept(actionId, payload);
            } catch (RuntimeException ignored) {
                // Malformed or stale client-owned actions must not affect the proxy event loop.
            }
        }
    }

    private Handle actionForConnection(String actionId, PacketReceiveEvent event) {
        for (Map.Entry<PlayerResourceKey, Handle> entry : actions.entrySet()) {
            if (!entry.getKey().name.equals(actionId)) continue;
            Object expectedPlayer = nativePlayer.apply(entry.getKey().playerId);
            if (expectedPlayer == null) continue;
            if (expectedPlayer == event.getPlayer()) return entry.getValue();
            try {
                if (packets.getProtocolManager().getUser(expectedPlayer) == event.getUser()) {
                    return entry.getValue();
                }
            } catch (RuntimeException ignored) {
                // The player may be between Bungee login states; UUID/direct identity checks remain available.
            }
        }
        return null;
    }

    private static UUID platformPlayerId(Object player) {
        if (player == null) return null;
        try {
            Object value = player.getClass().getMethod("getUniqueId").invoke(player);
            return value instanceof UUID uniqueId ? uniqueId : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Map<String, Object> values(NBT payload) {
        if (!(payload instanceof NBTCompound compound)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        compound.getTags().forEach((key, value) -> result.put(key, value(value)));
        return Map.copyOf(result);
    }

    private static Object value(NBT value) {
        if (value instanceof NBTString string) return string.getValue();
        if (value instanceof NBTNumber number) return number.getAsNumber();
        if (value instanceof NBTCompound compound) return values(compound);
        if (value instanceof NBTList<?> list) return list.getTags().stream().map(PacketEventsPlayerDialogs::value).toList();
        return value.toString();
    }

    private static List<DialogButton> buttons(DialogType type) {
        if (type instanceof DialogType.Notice value) return List.of(value.action());
        if (type instanceof DialogType.Confirmation value) return List.of(value.yes(), value.no());
        if (type instanceof DialogType.MultiAction value) {
            List<DialogButton> result = new ArrayList<>(value.actions());
            if (value.exitAction() != null) result.add(value.exitAction());
            return result;
        }
        if (type instanceof DialogType.ServerLinks value && value.exitAction() != null) return List.of(value.exitAction());
        if (type instanceof DialogType.DialogList value && value.exitAction() != null) return List.of(value.exitAction());
        return List.of();
    }
}
