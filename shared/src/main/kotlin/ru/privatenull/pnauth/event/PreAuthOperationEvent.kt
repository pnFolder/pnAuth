package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.extension.AuthOperationContext
import ru.privatenull.pnauth.kernel.event.AbstractDecisionEvent

class PreAuthOperationEvent(val context: AuthOperationContext) : AbstractDecisionEvent(), CancellableAuthEvent {
    fun context(): AuthOperationContext = context
}
