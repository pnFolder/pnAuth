package ru.privatenull.pnauth.kernel.service

import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DefaultServiceRegistry : ServiceRegistry {
    private val services = ConcurrentHashMap<ServiceKey<*>, CopyOnWriteArrayList<Entry<*>>>()

    override fun <T> register(
        key: ServiceKey<T>,
        ownerId: String,
        priority: Int,
        service: T
    ): ServiceRegistration {
        require(ownerId.isNotBlank()) { "ownerId is required" }
        require(priority in -5_000..5_000) { "priority must be between -5000 and 5000" }
        require(key.type.isInstance(service)) { "service does not implement ${key.type.name}" }

        val entries = services.computeIfAbsent(key) { CopyOnWriteArrayList() }
        val entry = Entry(ownerId, priority, service!!)
        entries.add(entry)
        entries.sortWith(Comparator.comparingInt<Entry<*>> { it.priority }.reversed())
        return ServiceRegistration {
            entries.remove(entry)
            if (entries.isEmpty()) services.remove(key, entries)
        }
    }

    override fun <T> find(key: ServiceKey<T>): Optional<T> {
        return findAll(key).stream().findFirst()
    }

    override fun <T> findAll(key: ServiceKey<T>): List<T> {
        val result = ArrayList<T>()
        val entries = services[key] ?: return emptyList()
        for (entry in entries) {
            result.add(key.type.cast(entry.service))
        }
        return result.toList()
    }

    private data class Entry<T>(val ownerId: String, val priority: Int, val service: T)
}
