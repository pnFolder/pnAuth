package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.kernel.event.EventListener
import ru.privatenull.pnauth.kernel.event.ExtensionEvent
import ru.privatenull.pnauth.kernel.event.ListenerOptions

/** Shared event API. Dispatch is synchronous on the thread completing the authentication operation. */
interface AuthEventBus {
    fun <E : ExtensionEvent> subscribe(type: Class<E>, listener: EventListener<in E>): AuthSubscription
    fun <E : ExtensionEvent> subscribe(
        type: Class<E>,
        options: ListenerOptions,
        listener: EventListener<in E>
    ): AuthSubscription

    fun publish(event: ExtensionEvent)
}
