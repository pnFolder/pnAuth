package ru.privatenull.pnauth.dialog

import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.platform.PnPlayer
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

class DialogFormTest {
    @Test
    fun generatesTransportIdsAndRoutesButtonWithoutExposingThem() {
        val submitted = AtomicReference<String>()
        val form = DialogForm.builder(Component.text("Verification"))
            .text("code", Component.text("Code"), 32, 200)
            .button(Component.text("Confirm")) { response ->
                submitted.set(response.string("code").orElseThrow())
            }
            .build()
        val dialogs = FakeDialogs()

        dialogs.show(player(), form)

        val type = assertInstanceOf(DialogType.MultiAction::class.java, dialogs.dialog!!.type)
        val action = assertInstanceOf(DialogAction.DynamicCustom::class.java, type.actions[0].action)
        assertTrue(action.id.startsWith("pnauth:form_"))
        dialogs.handle.publish(DialogResponse(action.id, mapOf("code" to "123456"), false))
        assertEquals("123456", submitted.get())
    }

    @Test
    fun createsFreshActionIdsEveryTimeFormIsShown() {
        val form = DialogForm.builder(Component.text("Test"))
            .button(Component.text("OK")) { }
            .build()
        val first = FakeDialogs()
        val second = FakeDialogs()

        first.show(player(), form)
        second.show(player(), form)

        assertNotEquals(actionId(first.dialog!!), actionId(second.dialog!!))
    }

    companion object {
        private fun actionId(dialog: PlayerDialog): String {
            val type = dialog.type as DialogType.MultiAction
            return (type.actions[0].action as DialogAction.DynamicCustom).id
        }

        private fun player(): PnPlayer {
            return Proxy.newProxyInstance(
                PnPlayer::class.java.classLoader,
                arrayOf(PnPlayer::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "uniqueId" -> UUID.randomUUID()
                    "username" -> "Player"
                    "connected" -> true
                    "hasPermission" -> false
                    "currentServer" -> Optional.empty<String>()
                    else -> null
                }
            } as PnPlayer
        }
    }

    private class FakeDialogs : PlayerDialogs {
        var dialog: PlayerDialog? = null
        val handle: FakeHandle = FakeHandle()

        override fun supported(player: PnPlayer): Boolean = true
        override fun show(player: PnPlayer, dialog: PlayerDialog): DialogHandle {
            this.dialog = dialog
            return handle
        }
        override fun find(playerId: UUID, dialogId: String): Optional<DialogHandle> = Optional.empty()
        override fun close(playerId: UUID, dialogId: String): Boolean = false
        override fun closeAll(playerId: UUID) {}
    }

    private class FakeHandle : DialogHandle {
        private val first = CompletableFuture<DialogResponse>()
        private val listeners = CopyOnWriteArrayList<Consumer<DialogResponse>>()
        private var active = true

        fun publish(response: DialogResponse) {
            first.complete(response)
            listeners.forEach { listener -> listener.accept(response) }
        }

        override fun playerId(): UUID = UUID(0, 1)
        override fun dialogId(): String = "test"
        override fun active(): Boolean = active
        override fun response(): CompletionStage<DialogResponse> = first
        override fun onResponse(listener: Consumer<DialogResponse>): DialogSubscription {
            listeners.add(listener)
            return DialogSubscription { listeners.remove(listener) }
        }
        override fun replace(dialog: PlayerDialog) {}
        override fun close() { active = false }
    }
}
