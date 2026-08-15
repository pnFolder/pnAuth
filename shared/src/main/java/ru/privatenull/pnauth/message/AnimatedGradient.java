package ru.privatenull.pnauth.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Produces one portable animated-gradient frame for every supported message format. */
public final class AnimatedGradient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AnimatedGradient() {
    }

    public static String frame(String renderedText, MessageFormat format, List<String> colors, int frame, int frameCount) {
        String plain = plain(renderedText, format);
        int[] points = plain.codePoints().toArray();
        if (points.length == 0) return "";
        ArrayNode json = format == MessageFormat.JSON ? JSON.createArrayNode() : null;
        StringBuilder result = new StringBuilder(plain.length() * 16);
        for (int index = 0; index < points.length; index++) {
            String color = color(colors, (index / (double) Math.max(1, points.length - 1)) + frame / (double) frameCount);
            String character = new String(Character.toChars(points[index]));
            switch (format) {
                case MINI_MESSAGE -> result.append('<').append(color).append('>').append(character).append("</").append(color).append('>');
                case LEGACY -> result.append(legacy(color)).append(character);
                case JSON -> {
                    ObjectNode part = json.addObject();
                    part.put("text", character);
                    part.put("color", color);
                }
                case PLAIN -> result.append(character);
            }
        }
        return json == null ? result.toString() : json.toString();
    }

    private static String plain(String value, MessageFormat format) {
        String legacy = MessageRenderers.toLegacy(value == null ? "" : value, format);
        return legacy.replaceAll("(?i)&x(?:&[0-9a-f]){6}", "").replaceAll("(?i)&[0-9a-fk-or]", "");
    }

    private static String color(List<String> colors, double position) {
        double wrapped = position - Math.floor(position);
        double scaled = wrapped * colors.size();
        int first = (int) Math.floor(scaled) % colors.size();
        int second = (first + 1) % colors.size();
        double ratio = scaled - Math.floor(scaled);
        int a = Integer.parseInt(colors.get(first).substring(1), 16);
        int b = Integer.parseInt(colors.get(second).substring(1), 16);
        int red = mix(a >> 16 & 255, b >> 16 & 255, ratio);
        int green = mix(a >> 8 & 255, b >> 8 & 255, ratio);
        int blue = mix(a & 255, b & 255, ratio);
        return String.format("#%02x%02x%02x", red, green, blue);
    }

    private static int mix(int first, int second, double ratio) {
        return (int) Math.round(first + (second - first) * ratio);
    }

    private static String legacy(String color) {
        StringBuilder result = new StringBuilder("&x");
        for (int index = 1; index < color.length(); index++) result.append('&').append(color.charAt(index));
        return result.append("&l").toString();
    }
}
