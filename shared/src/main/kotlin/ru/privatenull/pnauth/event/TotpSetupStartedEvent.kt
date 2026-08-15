package ru.privatenull.pnauth.event

import java.util.UUID

/** Deliberately excludes the TOTP secret and recovery codes. */
@JvmRecord
data class TotpSetupStartedEvent(
    private val _uniqueId: UUID,
    private val _username: String
) : UserAuthEvent {
    override fun uniqueId(): UUID = _uniqueId
    override fun username(): String = _username
}
