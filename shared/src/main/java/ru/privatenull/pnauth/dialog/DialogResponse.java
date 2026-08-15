package ru.privatenull.pnauth.dialog;

import java.util.Map;
import java.util.Optional;

/** Values submitted by a player through a dialog. */
public record DialogResponse(String action, Map<String, Object> values, boolean closed) {
    public DialogResponse { values = Map.copyOf(values); }

    /**
     * Returns a textual form value. Minecraft may encode text containing only a number or a
     * boolean as a scalar NBT tag, so transports expose those scalar values as their exact text.
     */
    public Optional<String> string(String id) {
        return scalarText(values.get(id));
    }

    private static Optional<String> scalarText(Object value) {
        if (value instanceof String text) return Optional.of(text);
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return Optional.of(String.valueOf(value));
        }
        if (value instanceof Map<?, ?> nested) {
            for (String preferredKey : new String[]{"value", "text", "input", "data", "contents", "result"}) {
                for (Map.Entry<?, ?> entry : nested.entrySet()) {
                    if (preferredKey.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                        Optional<String> preferred = scalarText(entry.getValue());
                        if (preferred.isPresent()) return preferred;
                    }
                }
            }
            String candidate = null;
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.equalsIgnoreCase("type") || key.equalsIgnoreCase("kind")
                        || key.equalsIgnoreCase("id") || key.equalsIgnoreCase("codec")
                        || key.equalsIgnoreCase("serializer")) continue;
                Optional<String> preferred = scalarText(entry.getValue());
                if (preferred.isPresent()) {
                    candidate = betterTextCandidate(candidate, preferred.get());
                }
            }
            return Optional.ofNullable(candidate);
        }
        if (value instanceof Iterable<?> nested) {
            String candidate = null;
            for (Object entry : nested) {
                Optional<String> preferred = scalarText(entry);
                if (preferred.isPresent()) {
                    candidate = betterTextCandidate(candidate, preferred.get());
                }
            }
            return Optional.ofNullable(candidate);
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            String candidate = null;
            for (int index = 0; index < length; index++) {
                Optional<String> preferred = scalarText(java.lang.reflect.Array.get(value, index));
                if (preferred.isPresent()) candidate = betterTextCandidate(candidate, preferred.get());
            }
            return Optional.ofNullable(candidate);
        }
        return Optional.empty();
    }

    private static String betterTextCandidate(String current, String candidate) {
        if (current == null) return candidate;
        if (current.isBlank() && !candidate.isBlank()) return candidate;
        if (looksLikeMetadata(current) && !looksLikeMetadata(candidate)) return candidate;
        if (looksLikeMetadata(current) == looksLikeMetadata(candidate) && candidate.length() > current.length()) {
            return candidate;
        }
        return current;
    }

    private static boolean looksLikeMetadata(String value) {
        return value.startsWith("minecraft:") || value.startsWith("pnauth:")
                || value.equalsIgnoreCase("string") || value.equalsIgnoreCase("text");
    }
    public Optional<Boolean> bool(String id) {
        Object value = values.get(id);
        if (value instanceof Boolean state) return Optional.of(state);
        if (value instanceof Number number) return Optional.of(number.intValue() != 0);
        if (value instanceof String text) {
            if (text.equalsIgnoreCase("true") || text.equals("1")) return Optional.of(true);
            if (text.equalsIgnoreCase("false") || text.equals("0")) return Optional.of(false);
        }
        return Optional.empty();
    }

    public Optional<Number> number(String id) {
        Object value = values.get(id);
        if (value instanceof Number number) return Optional.of(number);
        if (value instanceof String text) {
            try {
                return Optional.of(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
