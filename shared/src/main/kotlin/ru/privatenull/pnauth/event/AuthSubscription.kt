package ru.privatenull.pnauth.event

fun interface AuthSubscription : AutoCloseable {
    override fun close()
}
