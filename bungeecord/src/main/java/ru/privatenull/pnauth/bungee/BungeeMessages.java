package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.ChatColor;
import net.kyori.adventure.text.Component;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.message.MessageRenderers;

final class BungeeMessages {
    private BungeeMessages() {
    }

    static BaseComponent component(String message, MessageFormat format) {
        return TextComponent.fromArray(components(message, format));
    }

    static BaseComponent[] components(String message, MessageFormat format) {
        if (format == MessageFormat.JSON) {
            try {
                return net.md_5.bungee.chat.ComponentSerializer.parse(message == null ? "" : message);
            } catch (RuntimeException ignored) {
                // Fall through to inert legacy text for a malformed administrator template.
            }
        }
        String legacy = MessageRenderers.toLegacy(message == null ? "" : message,
                format == null ? MessageFormat.LEGACY : format);
        return TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', legacy));
    }

    static Component adventureComponent(String message, MessageFormat format) {
        String value = message == null ? "" : message;
        MessageFormat selected = format == null ? MessageFormat.LEGACY : format;
        String legacy = MessageRenderers.toLegacy(value, selected);
        String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', legacy));
        return Component.text(plain == null ? "" : plain);
    }
}
