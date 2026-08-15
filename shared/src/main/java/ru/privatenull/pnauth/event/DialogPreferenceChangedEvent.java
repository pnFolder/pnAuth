package ru.privatenull.pnauth.event;
import java.util.UUID;
import ru.privatenull.pnauth.api.DialogPreference;
public record DialogPreferenceChangedEvent(UUID uniqueId, String username, DialogPreference preference)
        implements UserAuthEvent { }
