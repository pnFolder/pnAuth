package ru.privatenull.pnauth.flow;
import java.util.UUID;
public record PlayerConnection(UUID uniqueId, String username, String ip) { }
