package ru.privatenull.pnauth.command;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface CommandService {
    List<CommandSpec> definitions();

    CompletionStage<List<String>> execute(CommandContext context);

    List<String> suggest(CommandContext context);
}
