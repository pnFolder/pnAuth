package ru.privatenull.pnauth.platform

/** Platform-agnostic logging facade for pnAuth bootstrap and extensions. */
interface PlatformLogger {
    fun info(message: String)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)

    companion object {
        @JvmStatic
        fun of(consumer: (String) -> Unit): PlatformLogger {
            return object : PlatformLogger {
                override fun info(message: String) = consumer(message)
                override fun warn(message: String, throwable: Throwable?) = consumer("[WARN] $message")
                override fun error(message: String, throwable: Throwable?) = consumer("[ERROR] $message")
            }
        }
    }
}
