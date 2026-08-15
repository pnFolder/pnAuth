package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.chat.BaseComponent;
import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.message.MessageFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BungeeMessagesTest {
    @Test
    void preservesJsonClickAndHoverEvents() {
        BaseComponent[] components = BungeeMessages.components("""
                {"text":"Open","clickEvent":{"action":"run_command","value":"/login password"},"hoverEvent":{"action":"show_text","contents":{"text":"Authenticate"}}}
                """, MessageFormat.JSON);

        assertEquals(1, components.length);
        assertEquals("/login password", components[0].getClickEvent().getValue());
        assertNotNull(components[0].getHoverEvent());
    }
}
