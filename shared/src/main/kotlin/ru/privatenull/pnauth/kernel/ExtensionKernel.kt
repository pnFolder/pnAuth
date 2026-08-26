package ru.privatenull.pnauth.kernel

import ru.privatenull.pnauth.display.PlayerDisplay
import ru.privatenull.pnauth.event.AuthEventBus
import ru.privatenull.pnauth.extension.AuthExtensionRegistry
import ru.privatenull.pnauth.kernel.service.ServiceRegistry
import ru.privatenull.pnauth.platform.Platform

/** Generic extension surface. Consumers do not need to depend on authentication services. */
interface ExtensionKernel {
    fun events(): AuthEventBus
    fun extensions(): AuthExtensionRegistry
    fun display(): PlayerDisplay
    fun platform(): Platform
    fun services(): ServiceRegistry
}
