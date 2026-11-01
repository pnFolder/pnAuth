package ru.privatenull.pnauth.velocity

import org.slf4j.Logger
import ru.privatenull.pnauth.log.Log

/** Запасной приёмник Log поверх логгера Velocity (SLF4J). */
internal class Slf4jSink(private val logger: Logger) : Log.Sink {
    override fun info(message: String) = logger.info(message)
    override fun success(message: String) = logger.info(message)
    override fun warning(message: String) = logger.warn(message)
    override fun error(message: String, error: Throwable?) {
        if (error == null) logger.error(message) else logger.error(message, error)
    }
}
