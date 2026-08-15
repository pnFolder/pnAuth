package ru.privatenull.pnauth.message

import java.util.Locale

enum class MessageFormat {
    LEGACY,
    MINI_MESSAGE,
    JSON,
    PLAIN;

    companion object {
        @JvmStatic
        fun parse(value: String?): MessageFormat {
            if (value.isNullOrBlank()) return LEGACY
            return when (value.trim().lowercase(Locale.ROOT).replace('-', '_')) {
                "legacy", "ampersand", "section" -> LEGACY
                "mini", "minimessage", "mini_message" -> MINI_MESSAGE
                "json", "component", "minecraft_json" -> JSON
                "plain", "text", "plain_text" -> PLAIN
                else -> throw IllegalArgumentException("Unknown message format: $value")
            }
        }
    }
}
