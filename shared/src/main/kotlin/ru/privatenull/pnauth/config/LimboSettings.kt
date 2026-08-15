package ru.privatenull.pnauth.config

@JvmRecord
data class LimboSettings(
    val provider: String,
    val enabled: Boolean,
    val serverName: String,
    val host: String,
    val port: Int,
    val autoDownload: Boolean,
    val downloadBaseUrl: String,
    val downloadSha256: String
) {
    init {
        if (provider.isBlank() || serverName.isBlank() || host.isBlank()
            || port < 1 || port > 65_535 || downloadBaseUrl.isBlank()
            || !downloadSha256.matches(Regex("(?i)[a-f0-9]{64}"))
        ) {
            throw IllegalArgumentException("Invalid limbo settings")
        }
    }

    companion object {
        const val OFFICIAL_DOWNLOAD_BASE_URL =
            "https://github.com/Quozul/PicoLimbo/releases/download/v1.13.2%2Bmc26.2/"
        const val OFFICIAL_DOWNLOAD_SHA256 =
            "1ba19f3ba52179a5eb20336bded8efa5f7967fea198927d1de49ebf190f3a527"

        @JvmStatic
        fun defaults(): LimboSettings {
            return LimboSettings(
                "pico", false, "auth", "127.0.0.1", 25_566, true,
                OFFICIAL_DOWNLOAD_BASE_URL, OFFICIAL_DOWNLOAD_SHA256
            )
        }
    }
}
