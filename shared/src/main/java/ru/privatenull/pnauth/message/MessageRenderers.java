package ru.privatenull.pnauth.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the built-in legacy templates to the configured wire format. */
public final class MessageRenderers {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final char MINI_OPEN_MARKER = '\u0000';
    private static final char MINI_CLOSE_MARKER = '\u0001';

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
            String source = template == null ? "" : template;
            if (format == MessageFormat.JSON) {
                String jsonTemplate = renderJsonTemplate(source, replacements);
                if (jsonTemplate != null) return jsonTemplate;
            }

            String value = replacePlaceholders(source, replacements, format == MessageFormat.MINI_MESSAGE);
            return switch (format) {
                case LEGACY -> value;
                case MINI_MESSAGE -> restoreMiniMarkers(toMiniMessage(value));
                case JSON -> toJson(value);
                case PLAIN -> stripFormatting(value);
            };
        }

        /**
         * JSON message templates are already component JSON.  Updating textual JSON
         * nodes rather than doing a string replacement keeps quotes and arbitrary
         * player supplied values from turning into JSON syntax.
         */
        private String renderJsonTemplate(String source, Map<String, String> replacements) {
            try {
                JsonNode root = JSON.readTree(source);
                if (root == null) return null;
                return JSON.writeValueAsString(replaceJsonText(root, replacements));
            } catch (JsonProcessingException ignored) {
                // A normal legacy message is not JSON and is converted below.
                return null;
            }
        }
    }

    /**
     * Converts a rendered message back to legacy only for older call sites which
     * cannot carry an Adventure component.  Platform adapters parse MINI_MESSAGE
     * and JSON directly, so hover/click events are not discarded there.
     */
    public static String toLegacy(String value, MessageFormat format) {
        if (value == null) return "";
        return switch (format == null ? MessageFormat.LEGACY : format) {
            case LEGACY -> value;
            case MINI_MESSAGE -> miniToLegacy(value);
            case JSON -> jsonToLegacy(value);
            case PLAIN -> value;
        };
    }

    private static JsonNode replaceJsonText(JsonNode value, Map<String, String> replacements) {
        if (value.isTextual()) {
            return JSON.getNodeFactory().textNode(replacePlaceholders(value.textValue(), replacements, false));
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            for (JsonNode item : value) result.add(replaceJsonText(item, replacements));
            return result;
        }
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            value.fields().forEachRemaining(entry -> result.set(entry.getKey(), replaceJsonText(entry.getValue(), replacements)));
            return result;
        }
        return value.deepCopy();
    }

    private static String replacePlaceholders(String template, Map<String, String> replacements, boolean protectMiniTags) {
        if (replacements == null || replacements.isEmpty()) return template;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder(template.length());
        int cursor = 0;
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!replacements.containsKey(key)) continue;
            result.append(template, cursor, matcher.start());
            String replacement = sanitizeReplacement(replacements.get(key));
            if (protectMiniTags) {
                replacement = replacement.replace('<', MINI_OPEN_MARKER).replace('>', MINI_CLOSE_MARKER);
            }
            result.append(replacement);
            cursor = matcher.end();
        }
        return cursor == 0 ? template : result.append(template, cursor, template.length()).toString();
    }

    private static String sanitizeReplacement(String value) {
        return stripFormatting(value == null ? "" : value);
    }

    private static String restoreMiniMarkers(String value) {
        return value.replace(String.valueOf(MINI_OPEN_MARKER), "\\<")
                .replace(String.valueOf(MINI_CLOSE_MARKER), "\\>");
    }

    private static String toMiniMessage(String value) {
        StringBuilder result = new StringBuilder();
        for (Segment segment : segments(value)) {
            appendMiniOpen(result, segment.style());
            result.append(escapeMiniText(segment.text()));
            appendMiniClose(result, segment.style());
        }
        return result.toString();
    }

    private static String toJson(String value) {
        ArrayNode result = JSON.createArrayNode();
        for (Segment segment : segments(value)) {
            ObjectNode part = result.addObject();
            part.put("text", segment.text());
            appendJsonStyle(part, segment.style());
        }
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not render message JSON", exception);
        }
    }

    private static void appendJsonStyle(ObjectNode node, Style style) {
        if (style.color() != null) node.put("color", style.color());
        if (style.bold()) node.put("bold", true);
        if (style.italic()) node.put("italic", true);
        if (style.underlined()) node.put("underlined", true);
        if (style.strikethrough()) node.put("strikethrough", true);
        if (style.obfuscated()) node.put("obfuscated", true);
    }

    private static void appendMiniOpen(StringBuilder result, Style style) {
        if (style.color() != null) result.append('<').append(style.color()).append('>');
        if (style.bold()) result.append("<bold>");
        if (style.italic()) result.append("<italic>");
        if (style.underlined()) result.append("<underlined>");
        if (style.strikethrough()) result.append("<strikethrough>");
        if (style.obfuscated()) result.append("<obfuscated>");
    }

    private static void appendMiniClose(StringBuilder result, Style style) {
        if (style.obfuscated()) result.append("</obfuscated>");
        if (style.strikethrough()) result.append("</strikethrough>");
        if (style.underlined()) result.append("</underlined>");
        if (style.italic()) result.append("</italic>");
        if (style.bold()) result.append("</bold>");
        if (style.color() != null) result.append("</").append(style.color()).append('>');
    }

    private static String stripFormatting(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            LegacyCode code = legacyCodeAt(value, index);
            if (code != null) {
                index = code.lastIndex();
            } else {
                result.append(value.charAt(index));
            }
        }
        return result.toString();
    }

    private static String miniToLegacy(String value) {
        String result = value;
        for (Map.Entry<String, String> color : COLOR_CODES.entrySet()) {
            result = result.replace("<" + color.getKey() + ">", color.getValue())
                    .replace("</" + color.getKey() + ">", "&r");
        }
        result = replaceMiniHexColors(result)
                .replaceAll("(?i)</#[0-9a-f]{6}>", "&r")
                .replaceAll("(?i)<(?:bold|b)>", "&l")
                .replaceAll("(?i)<(?:italic|i)>", "&o")
                .replaceAll("(?i)<(?:underlined|u)>", "&n")
                .replaceAll("(?i)<(?:strikethrough|st)>", "&m")
                .replaceAll("(?i)<(?:obfuscated|obf)>", "&k")
                .replaceAll("(?i)</(?:bold|b|italic|i|underlined|u|strikethrough|st|obfuscated|obf)>", "&r")
                .replaceAll("(?i)<reset>", "&r");
        return result;
    }

    private static String replaceMiniHexColors(String value) {
        Matcher matcher = Pattern.compile("(?i)<#([0-9a-f]{6})>").matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(hexLegacy(matcher.group(1))));
        }
        return matcher.appendTail(result).toString();
    }

    private static String jsonToLegacy(String value) {
        try {
            JsonNode root = JSON.readTree(value);
            if (root == null) return "";
            StringBuilder result = new StringBuilder();
            appendJsonLegacy(root, result, Style.EMPTY);
            return result.toString();
        } catch (JsonProcessingException ignored) {
            return stripFormatting(value);
        }
    }

    private static void appendJsonLegacy(JsonNode node, StringBuilder result, Style inherited) {
        if (node.isTextual()) {
            appendLegacySegment(result, new Segment(node.textValue(), inherited));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) appendJsonLegacy(child, result, inherited);
            return;
        }
        if (!node.isObject()) return;

        Style style = jsonStyle(node, inherited);
        JsonNode text = node.get("text");
        if (text != null && text.isTextual()) appendLegacySegment(result, new Segment(text.textValue(), style));
        JsonNode extra = node.get("extra");
        if (extra != null) appendJsonLegacy(extra, result, style);
    }

    private static Style jsonStyle(JsonNode node, Style inherited) {
        String color = node.hasNonNull("color") ? node.get("color").asText() : inherited.color();
        return new Style(color,
                node.has("bold") ? node.get("bold").asBoolean() : inherited.bold(),
                node.has("italic") ? node.get("italic").asBoolean() : inherited.italic(),
                node.has("underlined") ? node.get("underlined").asBoolean() : inherited.underlined(),
                node.has("strikethrough") ? node.get("strikethrough").asBoolean() : inherited.strikethrough(),
                node.has("obfuscated") ? node.get("obfuscated").asBoolean() : inherited.obfuscated());
    }

    private static void appendLegacySegment(StringBuilder result, Segment segment) {
        Style style = segment.style();
        if (style.color() != null) result.append(legacyColor(style.color()));
        if (style.bold()) result.append("&l");
        if (style.italic()) result.append("&o");
        if (style.underlined()) result.append("&n");
        if (style.strikethrough()) result.append("&m");
        if (style.obfuscated()) result.append("&k");
        result.append(segment.text());
    }

    private static String legacyColor(String color) {
        if (color.startsWith("#") && color.length() == 7) return hexLegacy(color.substring(1));
        return COLOR_CODES.getOrDefault(color.toLowerCase(Locale.ROOT), "");
    }

    private static String hexLegacy(String hex) {
        StringBuilder result = new StringBuilder("&x");
        for (int index = 0; index < hex.length(); index++) result.append('&').append(hex.charAt(index));
        return result.toString();
    }

    private static String escapeMiniText(String value) {
        StringBuilder result = new StringBuilder();
        int start = 0;
        Matcher matcher = MINI_TAG.matcher(value);
        while (matcher.find()) {
            result.append(escapeMiniLiteral(value.substring(start, matcher.start())));
            String tag = matcher.group(1).toLowerCase(Locale.ROOT);
            result.append(isMiniTag(tag) ? matcher.group() : escapeMiniLiteral(matcher.group()));
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
        return COLOR_CODES.containsKey(base)
                || base.matches("#[0-9a-f]{6}")
                || MINI_TAGS.contains(base);
    }

    private static List<Segment> segments(String value) {
        List<Segment> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Style style = Style.EMPTY;
        for (int index = 0; index < value.length(); index++) {
            LegacyCode code = legacyCodeAt(value, index);
            if (code != null) {
                if (text.length() > 0) {
                    result.add(new Segment(text.toString(), style));
                    text.setLength(0);
                }
                style = code.apply(style);
                index = code.lastIndex();
            } else {
                text.append(value.charAt(index));
            }
        }
        if (text.length() > 0 || result.isEmpty()) result.add(new Segment(text.toString(), style));
        return result;
    }

    private static LegacyCode legacyCodeAt(String value, int index) {
        if (index + 1 >= value.length()) return null;
        char prefix = value.charAt(index);
        if (prefix != '&' && prefix != '§') return null;
        char code = Character.toLowerCase(value.charAt(index + 1));
        String color = COLORS.get(String.valueOf(code));
        if (color != null) return new LegacyCode(index + 1, new Style(color, false, false, false, false, false));
        if (code == 'r') return new LegacyCode(index + 1, Style.EMPTY);
        if (code == 'k') return new LegacyCode(index + 1, null, Decoration.OBFUSCATED);
        if (code == 'l') return new LegacyCode(index + 1, null, Decoration.BOLD);
        if (code == 'm') return new LegacyCode(index + 1, null, Decoration.STRIKETHROUGH);
        if (code == 'n') return new LegacyCode(index + 1, null, Decoration.UNDERLINED);
        if (code == 'o') return new LegacyCode(index + 1, null, Decoration.ITALIC);
        if (code == '#' && index + 7 < value.length() && isHex(value, index + 2, 6)) {
            return new LegacyCode(index + 7, new Style("#" + value.substring(index + 2, index + 8).toLowerCase(Locale.ROOT),
                    false, false, false, false, false));
        }
        if (code == 'x') {
            StringBuilder hex = new StringBuilder(6);
            int cursor = index + 2;
            while (hex.length() < 6 && cursor + 1 < value.length()
                    && (value.charAt(cursor) == '&' || value.charAt(cursor) == '§')
                    && Character.digit(value.charAt(cursor + 1), 16) >= 0) {
                hex.append(value.charAt(cursor + 1));
                cursor += 2;
            }
            if (hex.length() == 6) {
                return new LegacyCode(cursor - 1, new Style("#" + hex.toString().toLowerCase(Locale.ROOT),
                        false, false, false, false, false));
            }
        }
        return null;
    }

    private static boolean isHex(String value, int start, int length) {
        for (int index = start; index < start + length; index++) {
            if (Character.digit(value.charAt(index), 16) < 0) return false;
        }
        return true;
    }

    private record Segment(String text, Style style) {
    }

    private record Style(String color, boolean bold, boolean italic, boolean underlined,
                         boolean strikethrough, boolean obfuscated) {
        private static final Style EMPTY = new Style(null, false, false, false, false, false);

        private Style with(Decoration decoration) {
            return switch (decoration) {
                case BOLD -> new Style(color, true, italic, underlined, strikethrough, obfuscated);
                case ITALIC -> new Style(color, bold, true, underlined, strikethrough, obfuscated);
                case UNDERLINED -> new Style(color, bold, italic, true, strikethrough, obfuscated);
                case STRIKETHROUGH -> new Style(color, bold, italic, underlined, true, obfuscated);
                case OBFUSCATED -> new Style(color, bold, italic, underlined, strikethrough, true);
            };
        }
    }

    private record LegacyCode(int lastIndex, Style replacement, Decoration decoration) {
        private LegacyCode(int lastIndex, Style replacement) {
            this(lastIndex, replacement, null);
        }

        private Style apply(Style previous) {
            return replacement != null ? replacement : previous.with(decoration);
        }
    }

    private enum Decoration {
        BOLD, ITALIC, UNDERLINED, STRIKETHROUGH, OBFUSCATED
    }

    private static final Pattern MINI_TAG = Pattern.compile("<(/?[a-z][a-z0-9_-]*(?::[^<>]*)?|/?#[0-9a-f]{6})>", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MINI_TAGS = Set.of(
            "reset", "bold", "b", "italic", "i", "underlined", "u", "strikethrough", "st",
            "obfuscated", "obf", "click", "hover", "insertion", "font", "gradient", "rainbow",
            "transition", "newline", "br", "keybind", "selector", "score", "nbt", "translatable",
            "lang", "fallback"
    );

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
