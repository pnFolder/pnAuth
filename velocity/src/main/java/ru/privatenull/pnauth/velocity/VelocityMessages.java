package ru.privatenull.pnauth.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.message.MessageRenderers;

final class VelocityMessages {
    private VelocityMessages() {
    }

    static Component component(String message) {
        return component(message, MessageFormat.LEGACY);
    }

    static Component component(String message, MessageFormat format) {
        if (format == MessageFormat.MINI_MESSAGE) {
            return MiniMessage.miniMessage().deserialize(message);
        }
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                .deserialize(MessageRenderers.toLegacy(message, format));
    }
}
