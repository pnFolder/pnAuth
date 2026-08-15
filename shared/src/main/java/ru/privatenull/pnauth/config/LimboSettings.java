package ru.privatenull.pnauth.config;

public record LimboSettings(
        String provider,
        boolean enabled,
        String serverName,
        String host,
        int port,
        boolean autoDownload,
        String downloadBaseUrl,
        String downloadSha256
) {
    public static final String OFFICIAL_DOWNLOAD_BASE_URL =
            "https://github.com/Quozul/PicoLimbo/releases/download/v1.13.2%2Bmc26.2/";
    public static final String OFFICIAL_DOWNLOAD_SHA256 =
            "1ba19f3ba52179a5eb20336bded8efa5f7967fea198927d1de49ebf190f3a527";
    public LimboSettings {
        if (provider == null || provider.isBlank() || serverName == null || serverName.isBlank() || host == null || host.isBlank()
                || port < 1 || port > 65_535 || downloadBaseUrl == null || downloadBaseUrl.isBlank()
                || downloadSha256 == null || !downloadSha256.matches("(?i)[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid limbo settings");
        }
    }

    public static LimboSettings defaults() {
        return new LimboSettings("pico", false, "auth", "127.0.0.1", 25_566, true,
                OFFICIAL_DOWNLOAD_BASE_URL, OFFICIAL_DOWNLOAD_SHA256);
    }
}
