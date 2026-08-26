package ru.privatenull.pnauth.api

import ru.privatenull.pnauth.extension.AuthExtensionRegistry

interface AuthExtensionApi {
    fun extensions(): AuthExtensionRegistry
}
