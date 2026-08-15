package ru.privatenull.pnauth.api

import ru.privatenull.pnauth.display.PlayerDisplay

interface AuthDisplayApi {
    fun display(): PlayerDisplay
}
