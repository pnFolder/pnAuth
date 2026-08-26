package ru.privatenull.pnauth.extension

fun interface AuthExtensionRegistration : AutoCloseable {
    override fun close()
}
