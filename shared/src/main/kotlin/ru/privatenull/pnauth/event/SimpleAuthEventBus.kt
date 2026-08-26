package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.kernel.event.DecisionEvent
import ru.privatenull.pnauth.kernel.event.EventDispatchRunner
import ru.privatenull.pnauth.kernel.event.EventListener
import ru.privatenull.pnauth.kernel.event.ExtensionEvent
import ru.privatenull.pnauth.kernel.event.ListenerOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BiConsumer

class SimpleAuthEventBus @JvmOverloads constructor(
    private val errorHandler: BiConsumer<ExtensionEvent, Throwable> = BiConsumer { _, _ -> }
) : AuthEventBus {

    private val listeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Registration>>()

    override fun <E : ExtensionEvent> subscribe(type: Class<E>, listener: EventListener<in E>): AuthSubscription {
        return subscribe(type, ListenerOptions.normal("anonymous"), listener)
    }

    override fun <E : ExtensionEvent> subscribe(
        type: Class<E>,
        options: ListenerOptions,
        listener: EventListener<in E>
    ): AuthSubscription {
        val registrations = listeners.computeIfAbsent(type) { CopyOnWriteArrayList() }
        val registration = Registration(options, listener)
        registrations.add(registration)
        registrations.sortWith(Comparator.comparingInt { it.options.priority.value })
        return AuthSubscription {
            registrations.remove(registration)
            if (registrations.isEmpty()) listeners.remove(type, registrations)
        }
    }

    override fun publish(event: ExtensionEvent) {
        val dispatch = ArrayList<Registration>()
        listeners.forEach { (type, registrations) ->
            if (type.isInstance(event)) dispatch.addAll(registrations)
        }
        dispatch.sortWith(Comparator.comparingInt { it.options.priority.value })
        for (registration in dispatch) {
            if (event is DecisionEvent && event.cancelled() && !registration.options.receiveCancelled) continue
            dispatch(registration, event)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatch(registration: Registration, event: ExtensionEvent) {
        try {
            EventDispatchRunner.run(registration.options) {
                (registration.listener as EventListener<ExtensionEvent>).onEvent(event)
            }
        } catch (error: Throwable) {
            errorHandler.accept(event, error)
        }
    }

    private data class Registration(val options: ListenerOptions, val listener: EventListener<*>)
}
