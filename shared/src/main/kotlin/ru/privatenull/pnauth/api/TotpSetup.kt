package ru.privatenull.pnauth.api

@JvmRecord
data class TotpSetup(
    val secret: String,
    val provisioningUri: String,
    val recoveryCodes: List<String>
)

