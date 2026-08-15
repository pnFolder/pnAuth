package ru.privatenull.pnauth.kernel.event

fun interface EventListener<E : ExtensionEvent> {
    fun onEvent(event: E)
}
