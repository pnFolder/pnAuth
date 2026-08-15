package ru.privatenull.pnauth.kernel.event;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
public abstract class AbstractDecisionEvent implements DecisionEvent {
    private final CopyOnWriteArrayList<EventDecision> history = new CopyOnWriteArrayList<>();
    private volatile EventDecision effective = new EventDecision(false, "kernel:default", EventPriority.LOWEST, "", Instant.now());
    @Override public boolean cancelled() { return effective.cancelled(); }
    @Override public void setCancelled(boolean cancelled) { setDecision(cancelled, ""); }
    @Override public synchronized void setDecision(boolean cancelled, String reason) {
        ListenerOptions actor = EventDispatchScope.current();
        if (actor == null) actor = ListenerOptions.normal("external:direct");
        if (actor.mode() == ListenerMode.MONITOR) throw new IllegalStateException("MONITOR listeners are read-only");
        EventDecision proposed = new EventDecision(cancelled, actor.ownerId(), actor.priority(),
                reason == null ? "" : reason, Instant.now());
        history.add(proposed);
        if (actor.priority().value() >= effective.priority().value()) effective = proposed;
    }
    @Override public EventDecision effectiveDecision() { return effective; }
    @Override public List<EventDecision> decisionHistory() { return List.copyOf(history); }
}
