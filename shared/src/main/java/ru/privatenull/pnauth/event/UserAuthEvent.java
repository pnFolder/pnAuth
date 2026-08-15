package ru.privatenull.pnauth.event;
import java.util.UUID;
public interface UserAuthEvent extends AuthEvent { UUID uniqueId(); String username(); }
