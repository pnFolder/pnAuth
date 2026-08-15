package ru.privatenull.pnauth.event;
import ru.privatenull.pnauth.extension.VerificationTicket;
public record VerificationRequiredEvent(VerificationTicket ticket) implements AuthEvent { }
