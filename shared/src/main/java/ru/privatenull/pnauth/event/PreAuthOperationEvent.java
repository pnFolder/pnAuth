package ru.privatenull.pnauth.event;
import ru.privatenull.pnauth.extension.AuthOperationContext;
import ru.privatenull.pnauth.kernel.event.AbstractDecisionEvent;
public final class PreAuthOperationEvent extends AbstractDecisionEvent implements CancellableAuthEvent {
    private final AuthOperationContext context;
    public PreAuthOperationEvent(AuthOperationContext context) { this.context = java.util.Objects.requireNonNull(context); }
    public AuthOperationContext context() { return context; }
}
