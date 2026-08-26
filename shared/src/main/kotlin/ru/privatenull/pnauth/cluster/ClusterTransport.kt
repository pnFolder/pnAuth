package ru.privatenull.pnauth.cluster

import java.util.function.Consumer

/**
 * Транспорт доставляет только события координации. Пароли, хеши, TOTP-секреты
 * и recovery-коды запрещено помещать в [ClusterEvent.attributes].
 */
interface ClusterTransport : AutoCloseable {
    fun publish(event: ClusterEvent)
    fun subscribe(listener: Consumer<ClusterEvent>): AutoCloseable
    fun healthy(): Boolean
    override fun close()
}
