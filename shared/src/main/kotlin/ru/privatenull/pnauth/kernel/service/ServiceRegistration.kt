package ru.privatenull.pnauth.kernel.service

fun interface ServiceRegistration : AutoCloseable {
    override fun close()
}
