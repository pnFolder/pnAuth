package ru.privatenull.pnauth.dialog;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.platform.PnPlayer;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DialogFormTest {
    @Test
    void canonicalLoginFormDoesNotRequireRegistrationConfirmation() {
        AtomicReference<AuthDialogFormFactory.Credentials> submitted = new AtomicReference<>();
        DialogForm form = AuthDialogFormFactory.create(AuthDialogFormFactory.Mode.LOGIN, true, 64,
                authContent(), submitted::set, () -> fail("Valid login response was rejected"));
        FakeDialogs dialogs = new FakeDialogs();

        dialogs.show(player(), form);
        assertEquals(1, dialogs.dialog.layout().inputs().size());
        dialogs.handle.publish(new DialogResponse(actionId(dialogs.dialog), Map.of("password", "secret"), false));

        assertEquals("secret", submitted.get().password());
        assertEquals("secret", submitted.get().confirmation());
    }

    @Test
    void canonicalRegistrationFormRequiresAndReturnsConfirmation() {
        AtomicReference<AuthDialogFormFactory.Credentials> submitted = new AtomicReference<>();
        DialogForm form = AuthDialogFormFactory.create(AuthDialogFormFactory.Mode.REGISTER, true, 64,
                authContent(), submitted::set, () -> fail("Valid registration response was rejected"));
        FakeDialogs dialogs = new FakeDialogs();

        dialogs.show(player(), form);
        assertEquals(2, dialogs.dialog.layout().inputs().size());
        dialogs.handle.publish(new DialogResponse(actionId(dialogs.dialog),
                Map.of("password", "secret", "confirmation", "secret"), false));

        assertEquals("secret", submitted.get().password());
        assertEquals("secret", submitted.get().confirmation());
    }

    @Test
    void generatesTransportIdsAndRoutesButtonWithoutExposingThem() {
        AtomicReference<String> submitted = new AtomicReference<>();
        DialogForm form = DialogForm.builder(Component.text("Verification"))
                .text("code", Component.text("Code"), 32, 200)
                .button(Component.text("Confirm"), response ->
                        submitted.set(response.string("code").orElseThrow()))
                .build();
        FakeDialogs dialogs = new FakeDialogs();

        dialogs.show(player(), form);

        DialogType.MultiAction type = assertInstanceOf(DialogType.MultiAction.class, dialogs.dialog.type());
        DialogAction.DynamicCustom action = assertInstanceOf(DialogAction.DynamicCustom.class,
                type.actions().get(0).action());
        assertTrue(action.id().startsWith("pnauth:form_"));
        dialogs.handle.publish(new DialogResponse(action.id(), Map.of("code", "123456"), false));
        assertEquals("123456", submitted.get());
    }

    @Test
    void createsFreshActionIdsEveryTimeFormIsShown() {
        DialogForm form = DialogForm.builder(Component.text("Test"))
                .button(Component.text("OK"), ignored -> { })
                .build();
        FakeDialogs first = new FakeDialogs();
        FakeDialogs second = new FakeDialogs();

        first.show(player(), form);
        second.show(player(), form);

        assertNotEquals(actionId(first.dialog), actionId(second.dialog));
    }

    private static String actionId(PlayerDialog dialog) {
        DialogType.MultiAction type = (DialogType.MultiAction) dialog.type();
        return ((DialogAction.DynamicCustom) type.actions().get(0).action()).id();
    }

    private static AuthDialogFormFactory.Content authContent() {
        return new AuthDialogFormFactory.Content(Component.text("Authentication"), Component.text("Description"),
                null, Component.text("Password"), Component.text("Repeat password"), Component.text("Submit"));
    }

    private static PnPlayer player() {
        return (PnPlayer) Proxy.newProxyInstance(PnPlayer.class.getClassLoader(), new Class<?>[]{PnPlayer.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "uniqueId" -> UUID.randomUUID();
                    case "username" -> "Player";
                    case "connected" -> true;
                    case "hasPermission" -> false;
                    case "currentServer" -> Optional.empty();
                    default -> null;
                });
    }

    private static final class FakeDialogs implements PlayerDialogs {
        private PlayerDialog dialog;
        private final FakeHandle handle = new FakeHandle();

        @Override public boolean supported(PnPlayer player) { return true; }
        @Override public DialogHandle show(PnPlayer player, PlayerDialog dialog) {
            this.dialog = dialog;
            return handle;
        }
        @Override public Optional<DialogHandle> find(UUID playerId, String dialogId) { return Optional.empty(); }
        @Override public boolean close(UUID playerId, String dialogId) { return false; }
        @Override public void closeAll(UUID playerId) { }
    }

    private static final class FakeHandle implements DialogHandle {
        private final CompletableFuture<DialogResponse> first = new CompletableFuture<>();
        private final CopyOnWriteArrayList<Consumer<DialogResponse>> listeners = new CopyOnWriteArrayList<>();
        private boolean active = true;

        void publish(DialogResponse response) {
            first.complete(response);
            listeners.forEach(listener -> listener.accept(response));
        }

        @Override public UUID playerId() { return new UUID(0, 1); }
        @Override public String dialogId() { return "test"; }
        @Override public boolean active() { return active; }
        @Override public CompletionStage<DialogResponse> response() { return first; }
        @Override public DialogSubscription onResponse(Consumer<DialogResponse> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
        @Override public void replace(PlayerDialog dialog) { }
        @Override public void close() { active = false; }
    }
}
