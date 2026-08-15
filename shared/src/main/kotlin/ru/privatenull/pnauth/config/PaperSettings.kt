package ru.privatenull.pnauth.config

/** Standalone Paper/Folia restrictions applied while a player is unauthenticated. */
@JvmRecord
data class PaperSettings(
    val teleportEnabled: Boolean,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val blockMovement: Boolean,
    val blockChat: Boolean,
    val blockCommands: Boolean,
    val blockInteraction: Boolean,
    val blockBreaking: Boolean,
    val blockPlacing: Boolean,
    val blockInventory: Boolean
)
