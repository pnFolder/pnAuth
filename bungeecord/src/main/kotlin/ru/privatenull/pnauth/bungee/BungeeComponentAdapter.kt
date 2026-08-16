package ru.privatenull.pnauth.bungee

import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.TextComponent
import java.awt.Color
import java.util.regex.Pattern

/**
 * Native BungeeCord converter that converts MiniMessage syntax (including multi-stop gradients,
 * hex colors, and formatting tags) directly into native BungeeCord BaseComponents with ChatColor.of("#RRGGBB").
 * Eliminates all external Adventure audience library dependencies and classloader conflicts.
 */
object BungeeComponentAdapter {

    private val GRADIENT_PATTERN = Pattern.compile("(?i)<gradient:((?:#[0-9a-f]{6}:?)+)>(.*?)</gradient>", Pattern.DOTALL)
    private val TAG_PATTERN = Pattern.compile("(?i)<(/?[a-z0-9_#]+)>")

    @JvmStatic
    fun parse(input: String?): Array<BaseComponent> {
        if (input.isNullOrEmpty()) return arrayOf(TextComponent(""))

        val withGradientsProcessed = processGradients(input)
        return parseFormattedText(withGradientsProcessed)
    }

    private fun processGradients(input: String): String {
        val matcher = GRADIENT_PATTERN.matcher(input)
        val sb = StringBuilder()
        var lastEnd = 0
        while (matcher.find()) {
            sb.append(input, lastEnd, matcher.start())
            val colorStops = matcher.group(1).split(":").filter { it.isNotBlank() }
            val innerText = matcher.group(2)
            sb.append(interpolateMultiGradient(colorStops, innerText))
            lastEnd = matcher.end()
        }
        sb.append(input, lastEnd, input.length)
        return sb.toString()
    }

    private fun interpolateMultiGradient(colorsHex: List<String>, text: String): String {
        if (text.isEmpty()) return ""
        val colors = colorsHex.mapNotNull { parseHex(it) }
        if (colors.isEmpty()) return text
        if (colors.size == 1) {
            val hex = String.format("#%06x", colors[0].rgb and 0xFFFFFF)
            return "<$hex>$text</$hex>"
        }

        val sb = StringBuilder()
        val segments = colors.size - 1
        val charsPerSegment = text.length.toDouble() / segments

        for (i in text.indices) {
            val segmentIndex = Math.min((i / charsPerSegment).toInt(), segments - 1)
            val startColor = colors[segmentIndex]
            val endColor = colors[segmentIndex + 1]
            val segmentPos = i - (segmentIndex * charsPerSegment)
            val localRatio = if (charsPerSegment <= 0) 0.0 else (segmentPos / charsPerSegment).coerceIn(0.0, 1.0)
            
            val r = (startColor.red + localRatio * (endColor.red - startColor.red)).toInt().coerceIn(0, 255)
            val g = (startColor.green + localRatio * (endColor.green - startColor.green)).toInt().coerceIn(0, 255)
            val b = (startColor.blue + localRatio * (endColor.blue - startColor.blue)).toInt().coerceIn(0, 255)
            val hex = String.format("#%02x%02x%02x", r, g, b)
            sb.append("<$hex>").append(text[i]).append("</$hex>")
        }
        return sb.toString()
    }

    private fun parseFormattedText(input: String): Array<BaseComponent> {
        val components = mutableListOf<BaseComponent>()
        var currentColor: ChatColor? = null
        var isBold = false
        var isItalic = false
        var isUnderlined = false
        var isStrikethrough = false
        var isObfuscated = false

        val matcher = TAG_PATTERN.matcher(input)
        var lastEnd = 0

        while (matcher.find()) {
            val textSegment = input.substring(lastEnd, matcher.start())
            if (textSegment.isNotEmpty()) {
                val comp = TextComponent(textSegment)
                if (currentColor != null) comp.color = currentColor
                if (isBold) comp.isBold = true
                if (isItalic) comp.isItalic = true
                if (isUnderlined) comp.isUnderlined = true
                if (isStrikethrough) comp.isStrikethrough = true
                if (isObfuscated) comp.isObfuscated = true
                components.add(comp)
            }

            val tag = matcher.group(1).lowercase()
            when {
                tag.startsWith("#") && tag.length == 7 -> {
                    currentColor = try { ChatColor.of(tag) } catch (e: Exception) { null }
                }
                tag == "/#" || tag.startsWith("/#") -> currentColor = null
                tag in listOf("bold", "b") -> isBold = true
                tag in listOf("/bold", "/b") -> isBold = false
                tag in listOf("italic", "i") -> isItalic = true
                tag in listOf("/italic", "/i") -> isItalic = false
                tag in listOf("underlined", "u") -> isUnderlined = true
                tag in listOf("/underlined", "/u") -> isUnderlined = false
                tag in listOf("strikethrough", "st") -> isStrikethrough = true
                tag in listOf("/strikethrough", "/st") -> isStrikethrough = false
                tag in listOf("obfuscated", "obf") -> isObfuscated = true
                tag in listOf("/obfuscated", "/obf") -> isObfuscated = false
                tag == "reset" || tag == "r" -> {
                    currentColor = null
                    isBold = false
                    isItalic = false
                    isUnderlined = false
                    isStrikethrough = false
                    isObfuscated = false
                }
                else -> {
                    val namedColor = try { ChatColor.of(tag) } catch (e: Exception) { null }
                    if (namedColor != null) {
                        currentColor = namedColor
                    }
                }
            }
            lastEnd = matcher.end()
        }

        val remainingText = input.substring(lastEnd)
        if (remainingText.isNotEmpty()) {
            val comp = TextComponent(remainingText)
            if (currentColor != null) comp.color = currentColor
            if (isBold) comp.isBold = true
            if (isItalic) comp.isItalic = true
            if (isUnderlined) comp.isUnderlined = true
            if (isStrikethrough) comp.isStrikethrough = true
            if (isObfuscated) comp.isObfuscated = true
            components.add(comp)
        }

        return if (components.isEmpty()) arrayOf(TextComponent("")) else components.toTypedArray()
    }

    private fun parseHex(hex: String): Color? {
        val clean = if (hex.startsWith("#")) hex.substring(1) else hex
        if (clean.length != 6) return null
        return try {
            val rgb = clean.toInt(16)
            Color(rgb)
        } catch (e: Exception) {
            null
        }
    }
}
