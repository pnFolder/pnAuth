package ru.privatenull.pnauth.kernel.event;
import java.time.Instant;
public record EventDecision(boolean cancelled, String ownerId, EventPriority priority, String reason, Instant at) { }
