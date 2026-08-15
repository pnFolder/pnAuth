package ru.privatenull.pnauth.command

import java.util.UUID

interface AuthPlatformBridge {
    fun authenticated(uniqueId: UUID)

    fun loggedOut(uniqueId: UUID)

    fun accountDeleted(uniqueId: UUID)

    fun authenticated(username: String) {}

    fun accountDeleted(username: String) {}

    /** Sends a pre-rendered announcement to every player connected to the proxy. */
    fun broadcast(message: String) {}

    fun apply(effect: CommandEffect, uniqueId: UUID) {
        when (effect) {
            CommandEffect.AUTHENTICATED -> authenticated(uniqueId)
            CommandEffect.LOGGED_OUT -> loggedOut(uniqueId)
            CommandEffect.ACCOUNT_DELETED -> accountDeleted(uniqueId)
            CommandEffect.NONE -> {}
        }
    }

    companion object {
        @JvmField
        val NONE: AuthPlatformBridge = object : AuthPlatformBridge {
            override fun authenticated(uniqueId: UUID) {}
            override fun loggedOut(uniqueId: UUID) {}
            override fun accountDeleted(uniqueId: UUID) {}
        }
    }
}
