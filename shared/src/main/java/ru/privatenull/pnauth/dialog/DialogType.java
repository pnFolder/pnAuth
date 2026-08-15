package ru.privatenull.pnauth.dialog;

import java.util.List;

/** Exact type-specific footer/layout variants supported by vanilla Minecraft. */
public sealed interface DialogType permits DialogType.Notice, DialogType.Confirmation,
        DialogType.MultiAction, DialogType.ServerLinks, DialogType.DialogList {
    record Notice(DialogButton action) implements DialogType { }
    record Confirmation(DialogButton yes, DialogButton no) implements DialogType { }
    record MultiAction(List<DialogButton> actions, DialogButton exitAction, int columns) implements DialogType {
        public MultiAction { actions = List.copyOf(actions); }
    }
    record ServerLinks(DialogButton exitAction, int columns, int buttonWidth) implements DialogType { }
    record DialogList(List<PlayerDialog> dialogs, String dialogTag, DialogButton exitAction,
                      int columns, int buttonWidth) implements DialogType {
        public DialogList { dialogs = List.copyOf(dialogs); }
    }
}
