package ru.privatenull.pnauth.dialog;

/** Complete platform-neutral description of a vanilla Minecraft dialog. */
public record PlayerDialog(String id, DialogLayout layout, DialogType type) { }
