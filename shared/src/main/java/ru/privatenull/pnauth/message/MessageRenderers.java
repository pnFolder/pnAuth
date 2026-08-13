package ru.privatenull.pnauth.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MessageRenderers {
    private MessageRenderers() {
    }

    public static MessageRenderer forFormat(MessageFormat format) {
        MessageFormat selected = format == null ? MessageFormat.LEGACY : format;
        return new Renderer(selected);
    }

    private record Renderer(MessageFormat format) implements MessageRenderer {
        @Override
        public String render(String template) {
            return render(template, Map.of());
        }

        @Override
        public String render(String template, Map<String, String> replacements) {
            String value = replace(template == null ? "" : template, replacements);
            String rendered = switch (format) {
                case LEGACY -> value;
                case MINI_MESSAGE -> toMiniMessage(value);
                case JSON -> toJson(value);
                case PLAIN -> stripFormatting(value);
            };
            return restoreReplacementMarkers(rendered);
        }

        private String replace(String template, Map<String, String> replacements) {
            if (replacements == null) return template;
            String value = template;
            for (Map.Entry<String, String> replacement : replacements.entrySet()) {
                value = value.replace("{" + replacement.getKey() + "}",
                        sanitizeReplacement(replacement.getValue()));
            }
            return value;
        }

        private String sanitizeReplacement(String value) {
            String sanitized = value == null ? "" : value.replaceAll("(?i)[&§][0-9a-fk-or]", "");
            return format == MessageFormat.MINI_MESSAGE
                    ? sanitized.replace("<", "\u0000").replace(">", "\u0001") : sanitized;
        }

        private String restoreReplacementMarkers(String value) {
            if (format == MessageFormat.MINI_MESSAGE) {
                return value.replace("\u0000", "\\<").replace("\u0001", "\\>");
            }
            return value.replace("\u0000", "<").replace("\u0001", ">");
        }
    }

    public static String toLegacy(String value, MessageFormat format) {
        if (value == null) return "";
        return switch (format == null ? MessageFormat.LEGACY : format) {
            case LEGACY -> value;
            case MINI_MESSAGE -> miniToLegacy(value);
            case JSON -> jsonToLegacy(value);
            case PLAIN -> value;
        };
    }

    private static String toMiniMessage(String value) {
        StringBuilder result = new StringBuilder();
        for (Segment segment : segments(value)) {
            if (segment.color() == null) {
                result.append(escapeMiniText(segment.text()));
            } else {
                result.append('<').append(segment.color()).append('>')
                        .append(escapeMiniText(segment.text())).append("</").append(segment.color()).append('>');
            }
        }
        return result.toString();
    }

    private static String toJson(String value) {
        StringBuilder result = new StringBuilder("[");
        boolean first = true;
        for (Segment segment : segments(value)) {
            if (!first) result.append(',');
            first = false;
            result.append("{\"text\":\"").append(jsonEscape(segment.text())).append('"');
            if (segment.color() != null) {
                result.append(",\"color\":\"").append(segment.color()).append('"');
            }
            result.append('}');
        }
        return result.append(']').toString();
    }

    private static String stripFormatting(String value) {
        return value.replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }

    private static String miniToLegacy(String value) {
        String result = value;
        for (Map.Entry<String, String> color : COLORS.entrySet()) {
            result = result.replace("<" + color.getKey() + ">", color.getValue())
                    .replace("</" + color.getKey() + ">", "&r");
        }
        return result.replaceAll("(?i)<reset>", "&r");
    }

    private static String jsonToLegacy(String value) {
        StringBuilder result = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{\\\"text\\\":\\\"(.*?)\\\"(?:,\\\"color\\\":\\\"([a-z0-9_]+)\\\")?\\}")
                .matcher(value);
        while (matcher.find()) {
            String color = matcher.group(2);
            if (color != null) result.append(COLOR_CODES.getOrDefault(color, ""));
            result.append(jsonUnescape(matcher.group(1)));
        }
        return result.length() == 0 ? stripFormatting(value) : result.toString();
    }

    private static String escapeMiniText(String value) {
        StringBuilder result = new StringBuilder();
        int start = 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<(/?[a-z][a-z0-9_-]*(?::[^<>]*)?)>")
                .matcher(value);
        while (matcher.find()) {
            result.append(escapeMiniLiteral(value.substring(start, matcher.start())));
            String tag = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            if (isMiniTag(tag)) {
                result.append(matcher.group());
            } else {
                result.append(escapeMiniLiteral(matcher.group()));
            }
            start = matcher.end();
        }
        return result.append(escapeMiniLiteral(value.substring(start))).toString();
    }

    private static String escapeMiniLiteral(String value) {
        return value.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
    }

    private static boolean isMiniTag(String tag) {
        String name = tag.startsWith("/") ? tag.substring(1) : tag;
        int separator = name.indexOf(':');
        String base = separator < 0 ? name : name.substring(0, separator);
        return COLORS.containsValue(base) || Set.of(
                "reset", "bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st",
                "obfuscated", "obf", "click", "hover", "insertion", "font", "gradient", "rainbow",
                "transition", "newline", "br", "keybind", "selector", "score", "nbt", "translatable",
                "lang", "fallback"
        ).contains(base);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String jsonUnescape(String value) {
        return value.replace("\\\"", "\"").replace("\\n", "\n")
                .replace("\\r", "\r").replace("\\\\", "\\");
    }

    private static List<Segment> segments(String value) {
        List<Segment> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String color = null;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character == '&' || character == '§') && index + 1 < value.length()) {
                String next = String.valueOf(Character.toLowerCase(value.charAt(++index)));
                String nextColor = COLORS.get(next);
                if (nextColor != null || next.equals("r")) {
                    if (text.length() > 0) result.add(new Segment(text.toString(), color));
                    text.setLength(0);
                    color = next.equals("r") ? null : nextColor;
                    continue;
                }
                text.append(character).append(next);
                continue;
            }
            text.append(character);
        }
        if (text.length() > 0 || result.isEmpty()) result.add(new Segment(text.toString(), color));
        return result;
    }

    private record Segment(String text, String color) {
    }

    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("0", "black"), Map.entry("1", "dark_blue"), Map.entry("2", "dark_green"),
            Map.entry("3", "dark_aqua"), Map.entry("4", "dark_red"), Map.entry("5", "dark_purple"),
            Map.entry("6", "gold"), Map.entry("7", "gray"), Map.entry("8", "dark_gray"),
            Map.entry("9", "blue"), Map.entry("a", "green"), Map.entry("b", "aqua"),
            Map.entry("c", "red"), Map.entry("d", "light_purple"), Map.entry("e", "yellow"),
            Map.entry("f", "white")
    );

    private static final Map<String, String> COLOR_CODES = Map.ofEntries(
            Map.entry("black", "&0"), Map.entry("dark_blue", "&1"), Map.entry("dark_green", "&2"),
            Map.entry("dark_aqua", "&3"), Map.entry("dark_red", "&4"), Map.entry("dark_purple", "&5"),
            Map.entry("gold", "&6"), Map.entry("gray", "&7"), Map.entry("dark_gray", "&8"),
            Map.entry("blue", "&9"), Map.entry("green", "&a"), Map.entry("aqua", "&b"),
            Map.entry("red", "&c"), Map.entry("light_purple", "&d"), Map.entry("yellow", "&e"),
            Map.entry("white", "&f")
    );
}
