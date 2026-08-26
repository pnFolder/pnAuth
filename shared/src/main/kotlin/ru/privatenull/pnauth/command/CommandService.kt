package ru.privatenull.pnauth.command

import java.util.concurrent.CompletionStage

interface CommandService {
    fun definitions(): List<CommandSpec>

    fun execute(context: CommandContext): CompletionStage<List<String>>

    fun suggest(context: CommandContext): List<String>
}
