package ru.privatenull.pnauth.kernel.service

import java.util.Optional

interface ServiceRegistry {
    fun <T> register(key: ServiceKey<T>, ownerId: String, priority: Int, service: T): ServiceRegistration
    fun <T> find(key: ServiceKey<T>): Optional<T>
    fun <T> findAll(key: ServiceKey<T>): List<T>
}
