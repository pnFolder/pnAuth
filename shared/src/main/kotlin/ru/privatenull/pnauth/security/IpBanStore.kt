package ru.privatenull.pnauth.security

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class IpBanStore @JvmOverloads constructor(
    private val clock: Clock = Clock.systemUTC()
) {
    private val bans: MutableMap<String, Long> = ConcurrentHashMap()

    fun ban(ip: String?, duration: Duration) {
        if (!ip.isNullOrBlank()) {
            bans[ip] = clock.millis() + duration.toMillis()
        }
    }

    fun isBanned(ip: String?): Boolean {
        if (ip == null) return false
        val until = bans[ip] ?: return false
        if (until <= clock.millis()) {
            bans.remove(ip, until)
            return false
        }
        return true
    }

    fun clear() {
        bans.clear()
    }
}
