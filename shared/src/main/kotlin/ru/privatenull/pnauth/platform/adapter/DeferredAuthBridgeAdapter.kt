package ru.privatenull.pnauth.platform.adapter

import ru.privatenull.pnauth.command.AuthPlatformBridge
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A late-bound auth bridge for platform bootstraps.
 *
 * pnAuth bootstraps require a bridge instance early (to build command service and messages),
 * while some platforms can only create their concrete action handlers after the bootstrap is ready.
 *
 * This adapter removes repetitive "var actions + forwarding object" boilerplate in each runtime.
 */
class DeferredAuthBridgeAdapter(
    initial: AuthPlatformBridge = AuthPlatformBridge.NONE
) : PlatformAuthBridgeAdapter {
    private val delegateRef = AtomicReference<AuthPlatformBridge>(initial)

    fun bind(delegate: AuthPlatformBridge) {
        delegateRef.set(delegate)
    }

    private fun delegate(): AuthPlatformBridge = delegateRef.get()

    override fun authenticated(uniqueId: UUID) = delegate().authenticated(uniqueId)
    override fun authenticated(uniqueId: UUID, isRegistration: Boolean) = delegate().authenticated(uniqueId, isRegistration)
    override fun authenticated(username: String) = delegate().authenticated(username)
    override fun loggedOut(uniqueId: UUID) = delegate().loggedOut(uniqueId)
    override fun accountDeleted(uniqueId: UUID) = delegate().accountDeleted(uniqueId)
    override fun accountDeleted(username: String) = delegate().accountDeleted(username)
    override fun broadcast(message: String) = delegate().broadcast(message)
}

