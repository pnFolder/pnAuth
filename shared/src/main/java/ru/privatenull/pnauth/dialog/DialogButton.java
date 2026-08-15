package ru.privatenull.pnauth.dialog;

import net.kyori.adventure.text.Component;

/** A button returned as the action identifier in a dialog response. */
public record DialogButton(Component label, Component tooltip, int width, DialogAction action) {
    public DialogButton(Component label, DialogAction action) { this(label, Component.empty(), 150, action); }
}
