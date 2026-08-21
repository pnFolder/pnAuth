package ru.privatenull.pnauth.config

import ru.privatenull.pnauth.extension.AuthOperation
import java.time.Duration

data class ExternalVerificationSettings(
    val enabled: Boolean,
    val operations: Set<AuthOperation>,
    val lifetime: Duration,
    val callback: Callback,
    val discord: Discord,
    val telegram: Telegram,
    val vk: Vk,
    val custom: Custom
) {
    data class Callback(val host: String, val port: Int, val publicUrl: String)
    data class Discord(val enabled: Boolean, val webhookUrl: String)
    data class Telegram(val enabled: Boolean, val botToken: String, val chatId: String)
    data class Vk(val enabled: Boolean, val accessToken: String, val peerId: String, val apiVersion: String)
    data class Custom(val enabled: Boolean, val url: String, val secret: String)

    init {
        require(!lifetime.isZero && !lifetime.isNegative) { "external-verification.lifetime-seconds must be positive" }
        require(callback.port in 1..65535) { "external-verification.callback.port must be between 1 and 65535" }
        if (enabled) {
            require(operations.isNotEmpty()) { "external-verification.operations must not be empty" }
            require(callback.publicUrl.startsWith("https://")) {
                "external-verification.callback.public-url must use HTTPS"
            }
            require(discord.enabled || telegram.enabled || vk.enabled || custom.enabled) {
                "external-verification requires at least one enabled provider"
            }
            if (discord.enabled) require(discord.webhookUrl.startsWith("https://")) { "Discord webhook URL must use HTTPS" }
            if (telegram.enabled) require(telegram.botToken.isNotBlank() && telegram.chatId.isNotBlank()) {
                "Telegram bot-token and chat-id are required"
            }
            if (vk.enabled) require(vk.accessToken.isNotBlank() && vk.peerId.isNotBlank()) {
                "VK access-token and peer-id are required"
            }
            if (custom.enabled) {
                require(custom.url.startsWith("https://")) { "Custom verification URL must use HTTPS" }
                require(custom.secret.length >= 32) { "Custom verification secret must contain at least 32 characters" }
            }
        }
    }
}
