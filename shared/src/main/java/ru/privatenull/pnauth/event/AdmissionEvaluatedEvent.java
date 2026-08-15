package ru.privatenull.pnauth.event;
import ru.privatenull.pnauth.api.AdmissionDecision;
public record AdmissionEvaluatedEvent(String username, String ip, AdmissionDecision decision) implements AuthEvent { }
