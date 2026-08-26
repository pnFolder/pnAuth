package ru.privatenull.pnauth.command

@JvmRecord
data class CommandSpec @JvmOverloads constructor(
    val name: String,
    val aliases: List<String> = emptyList()
) {
    init {
        requireNotNull(name)
    }
}
