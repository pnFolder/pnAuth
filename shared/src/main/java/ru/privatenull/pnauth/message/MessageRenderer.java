package ru.privatenull.pnauth.message;

import java.util.Map;

public interface MessageRenderer {
    MessageFormat format();

    String render(String template);

    String render(String template, Map<String, String> replacements);
}
