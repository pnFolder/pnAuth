package ru.privatenull.pnauth.limbo

import ru.privatenull.pnauth.config.LimboSettings
import java.nio.file.Path

@JvmRecord
data class LimboServerContext(val dataDirectory: Path, val settings: LimboSettings)
