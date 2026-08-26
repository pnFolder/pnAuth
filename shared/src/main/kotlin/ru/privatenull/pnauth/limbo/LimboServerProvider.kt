package ru.privatenull.pnauth.limbo

interface LimboServerProvider {
    fun id(): String

    fun create(context: LimboServerContext): LimboServer
}
