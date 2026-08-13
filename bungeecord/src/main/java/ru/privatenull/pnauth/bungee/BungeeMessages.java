package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.BaseComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.message.MessageRenderers;

final class BungeeMessages {
    private BungeeMessages() {
    }

    static BaseComponent component(String message, MessageFormat format) {
        if (format == MessageFormat.MINI_MESSAGE) {
            return TextComponent.fromArray(BungeeComponentSerializer.get()
                    .serialize(MiniMessage.miniMessage().deserialize(message)));
        }
        return TextComponent.fromLegacy(MessageRenderers.toLegacy(message, format));
    }

    static BaseComponent[] components(String message, MessageFormat format) {
        if (format == MessageFormat.MINI_MESSAGE) {
            return BungeeComponentSerializer.get().serialize(MiniMessage.miniMessage().deserialize(message));
        }
        return TextComponent.fromLegacyText(MessageRenderers.toLegacy(message, format));
    }
}
