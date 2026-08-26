package ru.privatenull.pnauth.ui

import net.kyori.adventure.text.Component

/** Converts localized pnAuth messages into the configured component format. */
interface AuthUiRenderer {
    fun render(key: String, replacements: Map<String, String>): Component
    fun renderText(text: String): Component

    fun render(key: String): Component = render(key, emptyMap())
}
