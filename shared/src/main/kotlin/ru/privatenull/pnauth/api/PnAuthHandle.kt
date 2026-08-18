package ru.privatenull.pnauth.api

import ru.privatenull.pnauth.kernel.ExtensionKernel
import ru.privatenull.pnauth.platform.Platform
import ru.privatenull.pnauth.platform.Proxy

/**
 * High-level pnAuth access point intended for platform bootstraps.
 *
 * The core API (`AuthApi`) and the generic extension kernel (`ExtensionKernel`) are always available.
 * Platform-specific facades may be unavailable depending on where pnAuth is running (proxy vs server).
 */
interface PnAuthHandle : AutoCloseable {
    fun api(): AuthApi

    fun kernel(): ExtensionKernel

    fun platform(): Platform?

    fun proxy(): Proxy?
}
