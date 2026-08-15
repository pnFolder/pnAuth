package ru.privatenull.pnauth.api

import ru.privatenull.pnauth.event.AuthEventBus

interface AuthEventApi {
    fun events(): AuthEventBus
}
