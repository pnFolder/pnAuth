package ru.privatenull.pnauth.event;
import java.util.UUID;
public record PremiumStateChangedEvent(UUID uniqueId, String username, boolean premium) implements UserAuthEvent { }
