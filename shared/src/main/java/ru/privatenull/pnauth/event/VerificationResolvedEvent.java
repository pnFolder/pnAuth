package ru.privatenull.pnauth.event;
import ru.privatenull.pnauth.extension.VerificationTicket;
public record VerificationResolvedEvent(VerificationTicket ticket) implements AuthEvent { }
