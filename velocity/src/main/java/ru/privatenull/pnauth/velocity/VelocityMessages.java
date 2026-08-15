package ru.privatenull.pnauth.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.message.MessageRenderers;

public final class VelocityMessages {
    private VelocityMessages() {
    }

    public static Component component(String message) {
        return component(message, MessageFormat.LEGACY);
    }

    public static Component component(String message, MessageFormat format) {
        String value = message == null ? "" : message;
        MessageFormat selected = format == null ? MessageFormat.LEGACY : format;
        try {
            return switch (selected) {
                case MINI_MESSAGE -> MiniMessage.miniMessage().deserialize(value);
                case JSON -> GsonComponentSerializer.gson().deserialize(value);
                case PLAIN -> Component.text(value);
                case LEGACY -> LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(MessageRenderers.toLegacy(value, selected));
            };
        } catch (RuntimeException ignored) {
            // Keep a malformed user template harmless instead of failing a proxy event.
            return Component.text(MessageRenderers.toLegacy(value, selected));
        }
    }
}
