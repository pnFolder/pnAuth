package ru.privatenull.pnauth.kernel.event;
import java.util.List;
public interface DecisionEvent extends ExtensionEvent {
    boolean cancelled();
    void setCancelled(boolean cancelled);
    default void cancel() { setCancelled(true); }
    default void allow() { setCancelled(false); }
    void setDecision(boolean cancelled, String reason);
    EventDecision effectiveDecision();
    List<EventDecision> decisionHistory();
}
