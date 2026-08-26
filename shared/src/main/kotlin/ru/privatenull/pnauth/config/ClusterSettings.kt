package ru.privatenull.pnauth.config

import ru.privatenull.pnauth.cluster.ClusterMode

data class ClusterSettings(
    val mode: ClusterMode,
    val nodeId: String,
    val redis: Redis,
    val hub: Hub
) {
    data class Redis(val uri: String, val stream: String)
    data class Hub(val url: String, val clientId: String, val clientSecret: String, val connectTimeoutMillis: Int)

    init {
        require(nodeId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "cluster.node-id has invalid characters" }
        if (mode == ClusterMode.REDIS) {
            require(redis.uri.startsWith("redis://") || redis.uri.startsWith("rediss://")) {
                "cluster.redis.uri must use redis:// or rediss://"
            }
            require(redis.stream.isNotBlank()) { "Redis stream is required" }
        }
        if (mode == ClusterMode.HUB) {
            require(hub.url.startsWith("https://")) { "cluster.hub.url must use HTTPS" }
            require(hub.clientId.isNotBlank() && hub.clientSecret.length >= 32) {
                "Hub client-id and a client-secret of at least 32 characters are required"
            }
        }
    }
}
