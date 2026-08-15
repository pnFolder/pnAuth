package ru.privatenull.pnauth.command

@JvmRecord
data class CommandContext @JvmOverloads constructor(
    val source: CommandSource,
    val command: String = "",
    val arguments: List<String> = emptyList()
) {
    init {
        requireNotNull(source) { "source must not be null" }
    }
}
