package ru.privatenull.pnauth.message

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Converts the built-in legacy templates to the configured wire format. */
object MessageRenderers {
    private val JSON = ObjectMapper()
    private val PLACEHOLDER = Pattern.compile("\\{([^{}]+)}")
    private const val MINI_OPEN_MARKER = '\u0000'
    private const val MINI_CLOSE_MARKER = '\u0001'

    @JvmStatic
    fun forFormat(format: MessageFormat?): MessageRenderer {
        val selected = format ?: MessageFormat.LEGACY
        return Renderer(selected)
    }

    @JvmStatic
    fun toLegacy(value: String?, format: MessageFormat?): String {
        if (value == null) return ""
        return when (format ?: MessageFormat.LEGACY) {
            MessageFormat.LEGACY -> value
            MessageFormat.MINI_MESSAGE -> miniToLegacy(value)
            MessageFormat.JSON -> jsonToLegacy(value)
            MessageFormat.PLAIN -> value
        }
    }

    private class Renderer(private val format: MessageFormat) : MessageRenderer {
        override fun format(): MessageFormat = format

        override fun render(template: String?): String {
            return render(template, emptyMap())
        }

        override fun render(template: String?, replacements: Map<String, String>?): String {
            val source = template ?: ""
            if (format == MessageFormat.JSON) {
                val jsonTemplate = renderJsonTemplate(source, replacements ?: emptyMap())
                if (jsonTemplate != null) return jsonTemplate
            }

            val interactive = source.contains("<auth:", ignoreCase = true)
            val value = replacePlaceholders(source, replacements, format == MessageFormat.MINI_MESSAGE || interactive)
            // An auth action marks a complete MiniMessage document. Re-converting it as
            // legacy would escape nested hover tags and expose their markup to the player.
            if (interactive) return restoreMiniMarkers(value)
            return when (format) {
                MessageFormat.LEGACY -> value
                MessageFormat.MINI_MESSAGE -> restoreMiniMarkers(toMiniMessage(value))
                MessageFormat.JSON -> toJson(value)
                MessageFormat.PLAIN -> stripFormatting(value)
            }
        }

        private fun renderJsonTemplate(source: String, replacements: Map<String, String>): String? {
            return try {
                val root = JSON.readTree(source) ?: return null
                JSON.writeValueAsString(replaceJsonText(root, replacements))
            } catch (ignored: JsonProcessingException) {
                null
            }
        }
    }

    private fun replaceJsonText(value: JsonNode, replacements: Map<String, String>): JsonNode {
        if (value.isTextual) {
            return JSON.nodeFactory.textNode(replacePlaceholders(value.textValue(), replacements, false))
        }
        if (value.isArray) {
            val result = JSON.createArrayNode()
            for (item in value) result.add(replaceJsonText(item, replacements))
            return result
        }
        if (value.isObject) {
            val result = JSON.createObjectNode()
            value.fields().forEachRemaining { entry -> result.set<JsonNode>(entry.key, replaceJsonText(entry.value, replacements)) }
            return result
        }
        return value.deepCopy()
    }

    private fun replacePlaceholders(template: String, replacements: Map<String, String>?, protectMiniTags: Boolean): String {
        if (replacements.isNullOrEmpty()) return template
        val matcher = PLACEHOLDER.matcher(template)
        val result = StringBuilder(template.length)
        var cursor = 0
        while (matcher.find()) {
            val key = matcher.group(1)
            if (!replacements.containsKey(key)) continue
            result.append(template, cursor, matcher.start())
            var replacement = sanitizeReplacement(replacements[key])
            if (protectMiniTags) {
                replacement = replacement.replace('<', MINI_OPEN_MARKER).replace('>', MINI_CLOSE_MARKER)
            }
            result.append(replacement)
            cursor = matcher.end()
        }
        return if (cursor == 0) template else result.append(template, cursor, template.length).toString()
    }

    private fun sanitizeReplacement(value: String?): String {
        return stripFormatting(value ?: "")
    }

    private fun restoreMiniMarkers(value: String): String {
        return value.replace(MINI_OPEN_MARKER.toString(), "\\<")
            .replace(MINI_CLOSE_MARKER.toString(), "\\>")
    }

    private fun toMiniMessage(value: String): String {
        val result = StringBuilder()
        for (segment in segments(value)) {
            appendMiniOpen(result, segment.style)
            result.append(escapeMiniText(segment.text))
            appendMiniClose(result, segment.style)
        }
        return result.toString()
    }

    private fun toJson(value: String): String {
        val result = JSON.createArrayNode()
        for (segment in segments(value)) {
            val part = result.addObject()
            part.put("text", segment.text)
            appendJsonStyle(part, segment.style)
        }
        return try {
            JSON.writeValueAsString(result)
        } catch (exception: JsonProcessingException) {
            throw IllegalStateException("Could not render message JSON", exception)
        }
    }

    private fun appendJsonStyle(node: ObjectNode, style: Style) {
        if (style.color != null) node.put("color", style.color)
        if (style.bold) node.put("bold", true)
        if (style.italic) node.put("italic", true)
        if (style.underlined) node.put("underlined", true)
        if (style.strikethrough) node.put("strikethrough", true)
        if (style.obfuscated) node.put("obfuscated", true)
    }

    private fun appendMiniOpen(result: StringBuilder, style: Style) {
        if (style.color != null) result.append('<').append(style.color).append('>')
        if (style.bold) result.append("<bold>")
        if (style.italic) result.append("<italic>")
        if (style.underlined) result.append("<underlined>")
        if (style.strikethrough) result.append("<strikethrough>")
        if (style.obfuscated) result.append("<obfuscated>")
    }

    private fun appendMiniClose(result: StringBuilder, style: Style) {
        if (style.obfuscated) result.append("</obfuscated>")
        if (style.strikethrough) result.append("</strikethrough>")
        if (style.underlined) result.append("</underlined>")
        if (style.italic) result.append("</italic>")
        if (style.bold) result.append("</bold>")
        if (style.color != null) result.append("</").append(style.color).append('>')
    }

    private fun stripFormatting(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val code = legacyCodeAt(value, index)
            if (code != null) {
                index = code.lastIndex
            } else {
                result.append(value[index])
            }
            index++
        }
        return result.toString()
    }

    private fun miniToLegacy(value: String): String {
        var result = value
        for ((key, colorValue) in COLOR_CODES) {
            result = result.replace("<$key>", colorValue).replace("</$key>", "&r")
        }
        result = replaceMiniHexColors(result)
            .replace(Regex("(?i)<gradient:[^>]+>"), "&a&l")
            .replace(Regex("(?i)</gradient>"), "&r")
            .replace(Regex("(?i)</#[0-9a-f]{6}>"), "&r")
            .replace(Regex("(?i)<(?:bold|b)>"), "&l")
            .replace(Regex("(?i)<(?:italic|i)>"), "&o")
            .replace(Regex("(?i)<(?:underlined|u)>"), "&n")
            .replace(Regex("(?i)<(?:strikethrough|st)>"), "&m")
            .replace(Regex("(?i)<(?:obfuscated|obf)>"), "&k")
            .replace(Regex("(?i)</(?:bold|b|italic|i|underlined|u|strikethrough|st|obfuscated|obf)>"), "&r")
            .replace(Regex("(?i)<reset>"), "&r")
        return result
    }

    private fun replaceMiniHexColors(value: String): String {
        val matcher = Pattern.compile("(?i)<#([0-9a-f]{6})>").matcher(value)
        val result = StringBuilder()
        var cursor = 0
        while (matcher.find()) {
            result.append(value, cursor, matcher.start())
            result.append(hexLegacy(matcher.group(1)))
            cursor = matcher.end()
        }
        return if (cursor == 0) value else result.append(value, cursor, value.length).toString()
    }

    private fun jsonToLegacy(value: String): String {
        return try {
            val root = JSON.readTree(value) ?: return ""
            val result = StringBuilder()
            appendJsonLegacy(root, result, Style.EMPTY)
            result.toString()
        } catch (ignored: JsonProcessingException) {
            stripFormatting(value)
        }
    }

    private fun appendJsonLegacy(node: JsonNode, result: StringBuilder, inherited: Style) {
        if (node.isTextual) {
            appendLegacySegment(result, Segment(node.textValue(), inherited))
            return
        }
        if (node.isArray) {
            for (child in node) appendJsonLegacy(child, result, inherited)
            return
        }
        if (!node.isObject) return

        val style = jsonStyle(node, inherited)
        val text = node.get("text")
        if (text != null && text.isTextual) appendLegacySegment(result, Segment(text.textValue(), style))
        val extra = node.get("extra")
        if (extra != null) appendJsonLegacy(extra, result, style)
    }

    private fun jsonStyle(node: JsonNode, inherited: Style): Style {
        val color = if (node.hasNonNull("color")) node.get("color").asText() else inherited.color
        return Style(
            color,
            if (node.has("bold")) node.get("bold").asBoolean() else inherited.bold,
            if (node.has("italic")) node.get("italic").asBoolean() else inherited.italic,
            if (node.has("underlined")) node.get("underlined").asBoolean() else inherited.underlined,
            if (node.has("strikethrough")) node.get("strikethrough").asBoolean() else inherited.strikethrough,
            if (node.has("obfuscated")) node.get("obfuscated").asBoolean() else inherited.obfuscated
        )
    }

    private fun appendLegacySegment(result: StringBuilder, segment: Segment) {
        val style = segment.style
        if (style.color != null) result.append(legacyColor(style.color))
        if (style.bold) result.append("&l")
        if (style.italic) result.append("&o")
        if (style.underlined) result.append("&n")
        if (style.strikethrough) result.append("&m")
        if (style.obfuscated) result.append("&k")
        result.append(segment.text)
    }

    private fun legacyColor(color: String): String {
        if (color.startsWith("#") && color.length == 7) return hexLegacy(color.substring(1))
        return COLOR_CODES[color.lowercase(Locale.ROOT)] ?: ""
    }

    private fun hexLegacy(hex: String): String {
        val result = StringBuilder("&x")
        for (i in hex.indices) result.append('&').append(hex[i])
        return result.toString()
    }

    private fun escapeMiniText(value: String): String {
        val result = StringBuilder()
        var start = 0
        val matcher = MINI_TAG.matcher(value)
        while (matcher.find()) {
            result.append(escapeMiniLiteral(value.substring(start, matcher.start())))
            val tag = matcher.group(1).lowercase(Locale.ROOT)
            result.append(if (isMiniTag(tag)) matcher.group() else escapeMiniLiteral(matcher.group()))
            start = matcher.end()
        }
        return result.append(escapeMiniLiteral(value.substring(start))).toString()
    }

    private fun escapeMiniLiteral(value: String): String {
        return value.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>")
    }

    private fun isMiniTag(tag: String): Boolean {
        val name = if (tag.startsWith("/")) tag.substring(1) else tag
        val separator = name.indexOf(':')
        val base = if (separator < 0) name else name.substring(0, separator)
        return COLOR_CODES.containsKey(base) || base.matches(Regex("#[0-9a-f]{6}")) || MINI_TAGS.contains(base)
    }

    private fun segments(value: String): List<Segment> {
        val result = ArrayList<Segment>()
        val text = StringBuilder()
        var style = Style.EMPTY
        var index = 0
        while (index < value.length) {
            val code = legacyCodeAt(value, index)
            if (code != null) {
                if (text.isNotEmpty()) {
                    result.add(Segment(text.toString(), style))
                    text.setLength(0)
                }
                style = code.apply(style)
                index = code.lastIndex
            } else {
                text.append(value[index])
            }
            index++
        }
        if (text.isNotEmpty() || result.isEmpty()) result.add(Segment(text.toString(), style))
        return result
    }

    private fun legacyCodeAt(value: String, index: Int): LegacyCode? {
        if (index + 1 >= value.length) return null
        val prefix = value[index]
        if (prefix != '&' && prefix != '§') return null
        val code = Character.toLowerCase(value[index + 1])
        val color = COLORS[code.toString()]
        if (color != null) return LegacyCode(index + 1, Style(color, false, false, false, false, false))
        if (code == 'r') return LegacyCode(index + 1, Style.EMPTY)
        if (code == 'k') return LegacyCode(index + 1, null, Decoration.OBFUSCATED)
        if (code == 'l') return LegacyCode(index + 1, null, Decoration.BOLD)
        if (code == 'm') return LegacyCode(index + 1, null, Decoration.STRIKETHROUGH)
        if (code == 'n') return LegacyCode(index + 1, null, Decoration.UNDERLINED)
        if (code == 'o') return LegacyCode(index + 1, null, Decoration.ITALIC)
        if (code == '#' && index + 7 < value.length && isHex(value, index + 2, 6)) {
            return LegacyCode(
                index + 7,
                Style("#" + value.substring(index + 2, index + 8).lowercase(Locale.ROOT), false, false, false, false, false)
            )
        }
        if (code == 'x') {
            val hex = StringBuilder(6)
            var cursor = index + 2
            while (hex.length < 6 && cursor + 1 < value.length &&
                (value[cursor] == '&' || value[cursor] == '§') &&
                Character.digit(value[cursor + 1], 16) >= 0
            ) {
                hex.append(value[cursor + 1])
                cursor += 2
            }
            if (hex.length == 6) {
                return LegacyCode(
                    cursor - 1,
                    Style("#" + hex.toString().lowercase(Locale.ROOT), false, false, false, false, false)
                )
            }
        }
        return null
    }

    private fun isHex(value: String, start: Int, length: Int): Boolean {
        for (i in start until start + length) {
            if (Character.digit(value[i], 16) < 0) return false
        }
        return true
    }

    private data class Segment(val text: String, val style: Style)

    private data class Style(
        val color: String?,
        val bold: Boolean,
        val italic: Boolean,
        val underlined: Boolean,
        val strikethrough: Boolean,
        val obfuscated: Boolean
    ) {
        fun with(decoration: Decoration): Style {
            return when (decoration) {
                Decoration.BOLD -> Style(color, true, italic, underlined, strikethrough, obfuscated)
                Decoration.ITALIC -> Style(color, bold, true, underlined, strikethrough, obfuscated)
                Decoration.UNDERLINED -> Style(color, bold, italic, true, strikethrough, obfuscated)
                Decoration.STRIKETHROUGH -> Style(color, bold, italic, underlined, true, obfuscated)
                Decoration.OBFUSCATED -> Style(color, bold, italic, underlined, strikethrough, true)
            }
        }

        companion object {
            val EMPTY = Style(null, false, false, false, false, false)
        }
    }

    private data class LegacyCode(
        val lastIndex: Int,
        val replacement: Style?,
        val decoration: Decoration? = null
    ) {
        fun apply(previous: Style): Style {
            return replacement ?: (decoration?.let { previous.with(it) } ?: previous)
        }
    }

    private enum class Decoration {
        BOLD, ITALIC, UNDERLINED, STRIKETHROUGH, OBFUSCATED
    }

    private val MINI_TAG = Pattern.compile("<(/?[a-z][a-z0-9_-]*(?::[^<>]*)?|/?#[0-9a-f]{6})>", Pattern.CASE_INSENSITIVE)
    private val MINI_TAGS = setOf(
        "reset", "bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st",
        "obfuscated", "obf", "click", "hover", "insertion", "font", "gradient", "rainbow",
        "transition", "newline", "br", "keybind", "selector", "score", "nbt", "translatable",
        "lang", "fallback", "auth"
    )

    private val COLORS = mapOf(
        "0" to "black", "1" to "dark_blue", "2" to "dark_green",
        "3" to "dark_aqua", "4" to "dark_red", "5" to "dark_purple",
        "6" to "gold", "7" to "gray", "8" to "dark_gray",
        "9" to "blue", "a" to "green", "b" to "aqua",
        "c" to "red", "d" to "light_purple", "e" to "yellow",
        "f" to "white"
    )

    private val COLOR_CODES = mapOf(
        "black" to "&0", "dark_blue" to "&1", "dark_green" to "&2",
        "dark_aqua" to "&3", "dark_red" to "&4", "dark_purple" to "&5",
        "gold" to "&6", "gray" to "&7", "dark_gray" to "&8",
        "blue" to "&9", "green" to "&a", "aqua" to "&b",
        "red" to "&c", "light_purple" to "&d", "yellow" to "&e",
        "white" to "&f"
    )
}
