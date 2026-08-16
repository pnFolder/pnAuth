package ru.privatenull.pnauth.routing

import ru.privatenull.pnauth.platform.Proxy
import java.util.Optional
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/** Factory for creating multi-server routing balancers. */
object ServerBalancerFactory {

    fun create(
        mode: ServerBalancerMode,
        maxPlayersPerServer: Int = 100,
        serverLimits: Map<String, Int> = emptyMap()
    ): ServerBalancer {
        return when (mode) {
            ServerBalancerMode.LEAST_PLAYERS -> LeastPlayersBalancer()
            ServerBalancerMode.LOWEST_LOAD_PERCENT -> LowestLoadPercentBalancer(maxPlayersPerServer, serverLimits)
            ServerBalancerMode.FIRST_AVAILABLE -> FirstAvailableBalancer()
            ServerBalancerMode.ROUND_ROBIN -> RoundRobinBalancer()
            ServerBalancerMode.RANDOM -> RandomBalancer()
            ServerBalancerMode.FILLING -> FillingBalancer(maxPlayersPerServer, serverLimits)
        }
    }

    private class LeastPlayersBalancer : ServerBalancer {
        override fun selectServer(targets: List<String>, proxy: Proxy?): Optional<String> {
            if (targets.isEmpty()) return Optional.empty()
            if (proxy == null) return Optional.of(targets.first())

            var bestServer: String? = null
            var lowestCount = Int.MAX_VALUE

            for (target in targets) {
                val serverOpt = proxy.server(target)
                if (serverOpt.isPresent) {
                    val count = serverOpt.get().playerCount()
                    if (count < lowestCount) {
                        lowestCount = count
                        bestServer = target
                    }
                }
            }
            return Optional.ofNullable(bestServer ?: targets.firstOrNull())
        }
    }

    private class LowestLoadPercentBalancer(
        private val defaultMax: Int,
        private val limits: Map<String, Int>
    ) : ServerBalancer {
        override fun selectServer(targets: List<String>, proxy: Proxy?): Optional<String> {
            if (targets.isEmpty()) return Optional.empty()
            if (proxy == null) return Optional.of(targets.first())

            var bestServer: String? = null
            var lowestRatio = Double.MAX_VALUE

            for (target in targets) {
                val serverOpt = proxy.server(target)
                if (serverOpt.isPresent) {
                    val count = serverOpt.get().playerCount()
                    val maxCapacity = limits.getOrDefault(target, defaultMax).coerceAtLeast(1)
                    val ratio = count.toDouble() / maxCapacity.toDouble()
                    if (ratio < lowestRatio) {
                        lowestRatio = ratio
                        bestServer = target
                    }
                }
            }
            return Optional.ofNullable(bestServer ?: targets.firstOrNull())
        }
    }

    private class FirstAvailableBalancer : ServerBalancer {
        override fun selectServer(targets: List<String>, proxy: Proxy?): Optional<String> {
            if (targets.isEmpty()) return Optional.empty()
            if (proxy == null) return Optional.of(targets.first())

            for (target in targets) {
                if (proxy.server(target).isPresent) {
                    return Optional.of(target)
                }
            }
            return Optional.ofNullable(targets.firstOrNull())
        }
    }

    private class RoundRobinBalancer : ServerBalancer {
        private val index = AtomicInteger(0)

        override fun selectServer(targets: List<String>, proxy: Proxy?): Optional<String> {
            if (targets.isEmpty()) return Optional.empty()
            val available = if (proxy != null) {
                targets.filter { proxy.server(it).isPresent }
            } else targets

            val pool = if (available.isNotEmpty()) available else targets
            val nextIndex = Math.abs(index.getAndIncrement() % pool.size)
            return Optional.of(pool[nextIndex])
        }
    }

    private class RandomBalancer : ServerBalancer {
        override fun selectServer(targets: List<String>, proxy: Proxy?): Optional<String> {
            if (targets.isEmpty()) return Optional.empty()
            val available = if (proxy != null) {
                targets.filter { proxy.server(it).isPresent }
            } else targets

            val pool = if (available.isNotEmpty()) available else targets
            val randomIndex = ThreadLocalRandom.current().nextInt(pool.size)
            return Optional.of(pool[randomIndex])
        }
    }

    private class FillingBalancer(
        private val defaultMax: Int,
        private val limits: Map<String, Int>
    ) : ServerBalancer {
        override fun selectServer(targets: List<String>, proxy: Proxy?): Optional<String> {
            if (targets.isEmpty()) return Optional.empty()
            if (proxy == null) return Optional.of(targets.first())

            for (target in targets) {
                val serverOpt = proxy.server(target)
                if (serverOpt.isPresent) {
                    val count = serverOpt.get().playerCount()
                    val maxCapacity = limits.getOrDefault(target, defaultMax)
                    if (count < maxCapacity) {
                        return Optional.of(target)
                    }
                }
            }
            return Optional.ofNullable(targets.lastOrNull())
        }
    }
}
