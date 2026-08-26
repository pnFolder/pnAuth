package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.kernel.event.EventListener

fun interface AuthEventListener<E : AuthEvent> : EventListener<E>
