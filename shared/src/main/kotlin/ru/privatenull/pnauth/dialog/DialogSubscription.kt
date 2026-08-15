package ru.privatenull.pnauth.dialog

/** Registration for a stream of dialog responses. */
fun interface DialogSubscription : AutoCloseable {
    override fun close()
}
