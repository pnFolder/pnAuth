package ru.privatenull.pnauth.platform

import java.util.UUID

/** Shared identity for every named resource owned by a player. */
class PlayerResourceKey(
    @JvmField val playerId: UUID,
    resourceId: String
) {
    @JvmField val name: String = resourceId

    init {
        require(resourceId.isNotBlank()) { "resourceId" }
    }

    fun playerId(): UUID = playerId
    fun resourceId(): String = name

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayerResourceKey) return false
        return playerId == other.playerId && name == other.name
    }

    override fun hashCode(): Int {
        return 31 * playerId.hashCode() + name.hashCode()
    }

    override fun toString(): String {
        return "PlayerResourceKey(playerId=$playerId, name=$name)"
    }
}
