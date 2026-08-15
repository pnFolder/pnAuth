package ru.privatenull.pnauth.limbo

import java.nio.file.Files

class PicoLimboProvider : LimboServerProvider {
    override fun id(): String = "pico"

    override fun create(context: LimboServerContext): LimboServer {
        val store = PicoLimboConfigStore()
        val configFile = context.dataDirectory.resolve("pico_limbo.toml")
        val config = if (Files.exists(configFile)) store.load(configFile) else PicoLimboConfig()
        return PicoLimboServer(context.dataDirectory, context.dataDirectory, context.settings, config)
    }
}
