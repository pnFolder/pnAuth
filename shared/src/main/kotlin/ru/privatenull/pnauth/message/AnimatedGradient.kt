package ru.privatenull.pnauth.message

import java.awt.Color
import java.util.Locale

object AnimatedGradient {
    @JvmStatic
    fun frame(
        text: String,
        format: MessageFormat,
        colors: List<String>,
        step: Int,
        width: Int
    ): String {
        if (text.isEmpty() || colors.isEmpty()) return MessageRenderers.forFormat(format).render(text)
        val textLength = text.length
        val numColors = colors.size
        val parsedColors = colors.map { parseColor(it) }

        val builder = StringBuilder()
        if (format == MessageFormat.JSON) {
            builder.append("[")
        }

        val effectiveWidth = width.coerceAtLeast(1)
        for (i in 0 until textLength) {
            val progress = ((i + step) % effectiveWidth).toFloat() / effectiveWidth
            val scaled = progress * (numColors - 1)
            val index = scaled.toInt().coerceIn(0, numColors - 2)
            val factor = scaled - index
            val hexColor = interpolateHex(parsedColors[index], parsedColors[index + 1], factor)
            val char = text[i]

            when (format) {
                MessageFormat.LEGACY -> {
                    builder.append("&x")
                    for (c in hexColor.substring(1)) {
                        builder.append('&').append(c)
                    }
                    builder.append(char)
                }
                MessageFormat.MINI_MESSAGE -> {
                    builder.append("<").append(hexColor).append(">").append(char).append("</").append(hexColor).append(">")
                }
                MessageFormat.JSON -> {
                    if (i > 0) builder.append(",")
                    builder.append("{\"text\":\"").append(char).append("\",\"color\":\"").append(hexColor).append("\"}")
                }
                MessageFormat.PLAIN -> {
                    builder.append(char)
                }
            }
        }
        if (format == MessageFormat.JSON) {
            builder.append("]")
        }
        return builder.toString()
    }

    private fun parseColor(hex: String): Color {
        val c = hex.removePrefix("#")
        val r = c.substring(0, 2).toInt(16)
        val g = c.substring(2, 4).toInt(16)
        val b = c.substring(4, 6).toInt(16)
        return Color(r, g, b)
    }

    private fun interpolateHex(c1: Color, c2: Color, factor: Float): String {
        val r = (c1.red + (c2.red - c1.red) * factor).toInt().coerceIn(0, 255)
        val g = (c1.green + (c2.green - c1.green) * factor).toInt().coerceIn(0, 255)
        val b = (c1.blue + (c2.blue - c1.blue) * factor).toInt().coerceIn(0, 255)
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)
    }
}
