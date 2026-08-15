package ru.privatenull.pnauth.config;

/** Standalone Paper/Folia restrictions applied while a player is unauthenticated. */
public record PaperSettings(boolean teleportEnabled, String world, double x, double y, double z,
                            float yaw, float pitch, boolean blockMovement, boolean blockChat,
                            boolean blockCommands, boolean blockInteraction, boolean blockBreaking,
                            boolean blockPlacing, boolean blockInventory) { }
