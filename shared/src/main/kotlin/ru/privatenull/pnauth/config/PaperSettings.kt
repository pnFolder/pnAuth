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
    val successDestination: SuccessDestination,
    val successWorld: String,
    val successX: Double,
    val successY: Double,
    val successZ: Double,
    val successYaw: Float,
    val successPitch: Float,
    val successDelayMillis: Long,
    val blockMovement: Boolean,
    val blockChat: Boolean,
    val blockCommands: Boolean,
    val blockInteraction: Boolean,
    val blockBreaking: Boolean,
    val blockPlacing: Boolean,
    val blockInventory: Boolean
) {
    init {
        require(successDelayMillis >= 0) { "paper.success-teleport.delay-millis must not be negative" }
    }

    enum class SuccessDestination { ORIGINAL, SPAWN, CUSTOM, NONE }
}
