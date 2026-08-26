package ru.privatenull.pnauth.paper

import net.kyori.adventure.text.Component
import ru.privatenull.pnauth.message.AuthMessages
import ru.privatenull.pnauth.message.MessageComponents
import ru.privatenull.pnauth.ui.AuthUiRenderer

/** Paper component renderer backed exclusively by the editable pnAuth message catalog. */
internal class PaperAuthUiRenderer(private val messages: AuthMessages) : AuthUiRenderer {
    override fun render(key: String, replacements: Map<String, String>): Component =
        MessageComponents.deserialize(messages.text(key, replacements), messages.format)

    override fun renderText(text: String): Component =
        MessageComponents.deserialize(text, messages.format)
}
