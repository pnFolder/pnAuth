package ru.privatenull.pnauth.velocity;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.message.MessageFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VelocityMessagesTest {
    @Test
    void preservesJsonClickAndHoverEvents() {
        Component component = VelocityMessages.component("""
                {"text":"Open","clickEvent":{"action":"run_command","value":"/login password"},"hoverEvent":{"action":"show_text","contents":{"text":"Authenticate"}}}
                """, MessageFormat.JSON);

        assertEquals("/login password", component.clickEvent().value());
        assertNotNull(component.hoverEvent());
    }
}
