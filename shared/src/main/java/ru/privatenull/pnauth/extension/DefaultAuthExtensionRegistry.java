package ru.privatenull.pnauth.extension;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DefaultAuthExtensionRegistry implements AuthExtensionRegistry {
    private final CopyOnWriteArrayList<HookEntry> hooks = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, TicketState> tickets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<OperationKey, String> ticketsByOperation = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<java.util.function.Consumer<VerificationTicket>> ticketListeners = new CopyOnWriteArrayList<>();
    private final Clock clock;
    public DefaultAuthExtensionRegistry() { this(Clock.systemUTC()); }
    DefaultAuthExtensionRegistry(Clock clock) { this.clock = clock; }

    @Override public AuthExtensionRegistration register(String id, int priority, AuthPolicyHook hook) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("extension id is required");
        HookEntry entry = new HookEntry(id, priority, java.util.Objects.requireNonNull(hook));
        hooks.add(entry); hooks.sort(Comparator.comparingInt(HookEntry::priority).reversed());
        return () -> hooks.remove(entry);
    }

    @Override public CompletionStage<AuthPolicyDecision> evaluate(AuthOperationContext context) {
        OperationKey key = new OperationKey(context.uniqueId(), context.username(), context.operation(), context.phase());
        String existingId = ticketsByOperation.get(key);
        if (existingId != null) {
            TicketState existing = tickets.get(existingId);
            if (existing != null && existing.expiresAt > clock.millis()) {
                if (existing.status == VerificationTicket.Status.APPROVED && tickets.remove(existingId, existing)) {
                    ticketsByOperation.remove(key, existingId);
                    return CompletableFuture.completedFuture(AuthPolicyDecision.allow());
                }
                if (existing.status == VerificationTicket.Status.PENDING) {
                    return CompletableFuture.completedFuture(existing.decision);
                }
            }
            tickets.remove(existingId);
            ticketsByOperation.remove(key, existingId);
        }
        return evaluateHook(context, 0).thenApply(decision -> {
            if (decision.type() == AuthPolicyDecision.Type.REQUIRE_VERIFICATION) createTicket(key, context, decision);
            return decision;
        });
    }

    private CompletionStage<AuthPolicyDecision> evaluateHook(AuthOperationContext context, int index) {
        if (index >= hooks.size()) return CompletableFuture.completedFuture(AuthPolicyDecision.allow());
        CompletionStage<AuthPolicyDecision> result;
        try { result = hooks.get(index).hook.before(context); }
        catch (Throwable error) { return CompletableFuture.completedFuture(AuthPolicyDecision.deny("Extension failed")); }
        return result.handle((decision, error) -> error == null && decision != null
                        ? decision : AuthPolicyDecision.deny("Extension failed"))
                .thenCompose(decision -> decision.type() == AuthPolicyDecision.Type.ALLOW
                        ? evaluateHook(context, index + 1) : CompletableFuture.completedFuture(decision));
    }

    private void createTicket(OperationKey key, AuthOperationContext context, AuthPolicyDecision decision) {
        String id = UUID.randomUUID().toString();
        TicketState state = new TicketState(id, key, context, decision,
                clock.millis() + decision.lifetime().toMillis(), VerificationTicket.Status.PENDING);
        tickets.put(id, state);
        String previous = ticketsByOperation.put(key, id);
        if (previous != null) tickets.remove(previous);
        notifyTicket(state);
    }

    @Override public Optional<VerificationTicket> pending(UUID uniqueId) {
        long now = clock.millis();
        return tickets.values().stream().filter(ticket -> java.util.Objects.equals(ticket.context.uniqueId(), uniqueId))
                .filter(ticket -> ticket.expiresAt > now && ticket.status == VerificationTicket.Status.PENDING)
                .map(this::view).findFirst();
    }
    @Override public boolean approve(String ticketId) { return update(ticketId, VerificationTicket.Status.APPROVED); }
    @Override public boolean deny(String ticketId) { return update(ticketId, VerificationTicket.Status.DENIED); }
    private boolean update(String id, VerificationTicket.Status status) {
        TicketState state = tickets.get(id);
        if (state == null || state.expiresAt <= clock.millis() || state.status != VerificationTicket.Status.PENDING) return false;
        TicketState updated = state.withStatus(status);
        boolean changed = tickets.replace(id, state, updated);
        if (changed) notifyTicket(updated);
        return changed;
    }
    @Override public AuthExtensionRegistration onTicket(java.util.function.Consumer<VerificationTicket> listener) {
        java.util.Objects.requireNonNull(listener);
        ticketListeners.add(listener);
        return () -> ticketListeners.remove(listener);
    }
    private void notifyTicket(TicketState state) {
        VerificationTicket ticket = view(state);
        ticketListeners.forEach(listener -> {
            try { listener.accept(ticket); } catch (RuntimeException ignored) { }
        });
    }
    private VerificationTicket view(TicketState state) {
        return new VerificationTicket(state.id, state.decision.provider(), state.context.uniqueId(),
                state.context.username(), state.context.operation(), state.decision.message(),
                Instant.ofEpochMilli(state.expiresAt), state.status);
    }
    private record HookEntry(String id, int priority, AuthPolicyHook hook) { }
    private record OperationKey(UUID uniqueId, String username, AuthOperation operation, AuthPhase phase) { }
    private record TicketState(String id, OperationKey key, AuthOperationContext context, AuthPolicyDecision decision,
                               long expiresAt, VerificationTicket.Status status) {
        TicketState withStatus(VerificationTicket.Status value) { return new TicketState(id, key, context, decision, expiresAt, value); }
    }
}
