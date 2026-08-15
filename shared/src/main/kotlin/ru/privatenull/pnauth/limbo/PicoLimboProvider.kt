package ru.privatenull.pnauth.limbo

class PicoLimboProvider : LimboServerProvider {
    override fun id(): String = "pico"

    override fun create(context: LimboServerContext): LimboServer {
        return PicoLimboServer(context.dataDirectory, context.settings)
    }
}
