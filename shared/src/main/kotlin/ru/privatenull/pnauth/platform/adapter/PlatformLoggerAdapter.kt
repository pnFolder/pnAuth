package ru.privatenull.pnauth.platform.adapter

/** Standardized multi-platform adapter for logging messages. */
interface PlatformLoggerAdapter {
    fun info(message: String)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)

    companion object {
        @JvmStatic
        fun of(consumer: (String) -> Unit): PlatformLoggerAdapter {
            return object : PlatformLoggerAdapter {
                override fun info(message: String) = consumer(message)
                override fun warn(message: String, throwable: Throwable?) = consumer("[WARN] $message")
                override fun error(message: String, throwable: Throwable?) = consumer("[ERROR] $message")
            }
        }
    }
}
