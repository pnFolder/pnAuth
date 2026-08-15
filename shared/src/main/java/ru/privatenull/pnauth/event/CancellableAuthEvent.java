package ru.privatenull.pnauth.event;
import ru.privatenull.pnauth.kernel.event.DecisionEvent;
public interface CancellableAuthEvent extends AuthEvent, DecisionEvent {
    default String cancellationReason() { return effectiveDecision().reason(); }
    default void cancel(String reason) { setDecision(true, reason); }
}
