package ru.privatenull.pnauth.dialog

import java.util.UUID
import java.util.function.BiConsumer

/** Routes modern client custom click actions without exposing internal commands to the player. */
interface CustomActionTransport {
    fun onAction(actionId: String, handler: BiConsumer<UUID, Map<String, Any>>): AutoCloseable
}
