package ru.privatenull.pnauth.kernel.service

import java.util.Objects

data class ServiceKey<T>(val namespace: String, val name: String, val type: Class<T>) {
    init {
        require(namespace.matches(Regex("[a-z0-9_.-]+"))) { "invalid namespace" }
        require(name.matches(Regex("[a-z0-9_.-]+"))) { "invalid service name" }
        Objects.requireNonNull(type, "type")
    }

    fun id(): String = "$namespace:$name"

    companion object {
        @JvmStatic
        fun <T> of(namespace: String, name: String, type: Class<T>): ServiceKey<T> {
            return ServiceKey(namespace, name, type)
        }
    }
}
