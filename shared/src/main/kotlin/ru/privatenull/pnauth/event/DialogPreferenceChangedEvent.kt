package ru.privatenull.pnauth.event

import ru.privatenull.pnauth.api.DialogPreference
import java.util.UUID

@JvmRecord
data class DialogPreferenceChangedEvent(
    private val _uniqueId: UUID,
    private val _username: String,
    val preference: DialogPreference
) : UserAuthEvent {
    override fun uniqueId(): UUID = _uniqueId
    override fun username(): String = _username
}
