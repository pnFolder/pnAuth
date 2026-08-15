package ru.privatenull.pnauth.kernel.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DefaultServiceRegistryTest {
    interface LinkService { boolean linked(String username); }
    @Test void selectsHighestPriorityProviderAndFallsBackAfterUnregister() {
        DefaultServiceRegistry services = new DefaultServiceRegistry();
        ServiceKey<LinkService> key = ServiceKey.of("social", "discord-links", LinkService.class);
        LinkService fallback = username -> false;
        LinkService preferred = username -> true;
        services.register(key, "fallback-addon", 0, fallback);
        var registration = services.register(key, "discord-addon", 100, preferred);
        assertSame(preferred, services.find(key).orElseThrow());
        assertEquals(2, services.findAll(key).size());
        registration.close();
        assertSame(fallback, services.find(key).orElseThrow());
    }
}
