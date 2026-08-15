package ru.privatenull.pnauth.limbo;

import com.sun.jna.Pointer;
import ru.privatenull.pnauth.config.LimboSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PicoLimboServer implements LimboServer {
    private final Path dataDirectory;
    private final LimboSettings settings;
    private final Object nativeLock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "pnauth-limbo");
        thread.setDaemon(true);
        return thread;
    });
    private PicoLimboLibrary.NativeApi nativeApi;
    private Pointer cancellationToken;
    private volatile boolean running;
    private volatile LimboServerState state;
    private volatile String effectiveHost;
    private volatile int effectivePort;
    private volatile LimboControl control;

    public PicoLimboServer(Path dataDirectory, LimboSettings settings) {
        this.dataDirectory = dataDirectory.resolve("limbo");
        this.settings = settings;
        this.state = settings.enabled() ? LimboServerState.CREATED : LimboServerState.DISABLED;
        this.effectiveHost = settings.host();
        this.effectivePort = settings.port();
    }

    public synchronized void start() throws Exception {
        if (!settings.enabled() || running) return;
        state = LimboServerState.STARTING;
        try {
            Files.createDirectories(dataDirectory);
            Path config = dataDirectory.resolve("config.toml");
            PicoLimboConfigStore configStore = new PicoLimboConfigStore();
            configStore.prepareEmbedded(config, settings.host(), settings.port());
            PicoLimboConfig picoConfig = configStore.load(config);
            PicoLimboConfig.Endpoint endpoint = picoConfig.endpoint();
            if (!isLoopback(endpoint.host())) {
                throw new IOException("Embedded PicoLimbo must bind to a loopback address, got " + endpoint.host());
            }
            effectiveHost = endpoint.host();
            effectivePort = endpoint.port();
            if (effectivePort != settings.port()) {
                throw new IOException("Limbo port " + effectivePort
                        + " does not match limbo.port " + settings.port() + " in pnAuth config");
            }
            nativeApi = PicoLimboLibrary.load(dataDirectory, settings);
            cancellationToken = nativeApi.get_cancellation_token();
            control = embeddingControl(nativeApi);
            String[] arguments = {"pico_limbo_java_wrapper", "--config", config.toString()};
            running = true;
            executor.execute(() -> {
                try {
                    nativeApi.start_app(cancellationToken, arguments.length, arguments);
                } finally {
                    synchronized (nativeLock) {
                        running = false;
                        state = LimboServerState.STOPPED;
                        if (cancellationToken != null) nativeApi.cleanup_token(cancellationToken);
                        cancellationToken = null;
                    }
                }
            });
            waitUntilReady();
            state = LimboServerState.RUNNING;
        } catch (Exception exception) {
            running = false;
            state = LimboServerState.FAILED;
            throw exception;
        }
    }

    public synchronized void stop() {
        synchronized (nativeLock) {
            if (nativeApi != null && cancellationToken != null) nativeApi.stop_app(cancellationToken);
            control = null;
            running = false;
        }
        if (state != LimboServerState.DISABLED) state = LimboServerState.STOPPED;
    }

    @Override
    public String id() {
        return settings.serverName();
    }

    @Override
    public LimboServerState state() {
        return state;
    }

    @Override
    public Optional<LimboControl> control() {
        return Optional.ofNullable(control);
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public boolean running() {
        return running;
    }

    public String serverName() {
        return settings.serverName();
    }

    public String host() {
        return effectiveHost;
    }

    public int port() {
        return effectivePort;
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }

    private LimboControl embeddingControl(PicoLimboLibrary.NativeApi api) {
        try {
            int version = api.pico_embedding_api_version();
            return version >= 1 ? new PicoLimboControl(api, version) : null;
        } catch (UnsatisfiedLinkError ignored) {
            return null;
        }
    }

    private void waitUntilReady() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        InetSocketAddress address = new InetSocketAddress(effectiveHost, effectivePort);
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(address, 100);
                return;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for PicoLimbo", interrupted);
                }
            }
        }
        throw new IOException("PicoLimbo did not become ready on " + address);
    }

    private static boolean isLoopback(String host) {
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException exception) {
            return false;
        }
    }

    private final class PicoLimboControl implements LimboControl {
        private final PicoLimboLibrary.NativeApi api;
        private final int version;

        private PicoLimboControl(PicoLimboLibrary.NativeApi api, int version) {
            this.api = api;
            this.version = version;
        }

        @Override
        public int apiVersion() {
            return version;
        }

        @Override
        public boolean isPlayerConnected(UUID playerId) {
            synchronized (nativeLock) {
                return api.pico_player_is_connected(handle(), required(playerId, "playerId").toString()) != 0;
            }
        }

        @Override
        public LimboControlResult addBossBar(UUID playerId, UUID barId, String title, float progress,
                                             LimboBossBarColor color, LimboBossBarOverlay overlay) {
            synchronized (nativeLock) {
                return result(api.pico_bossbar_add(handle(), required(playerId, "playerId").toString(),
                        required(barId, "barId").toString(), Objects.requireNonNull(title, "title"), progress,
                        Objects.requireNonNull(color, "color").id(),
                        Objects.requireNonNull(overlay, "overlay").id()));
            }
        }

        @Override
        public LimboControlResult updateBossBarProgress(UUID playerId, UUID barId, float progress) {
            synchronized (nativeLock) {
                return result(api.pico_bossbar_progress(handle(), required(playerId, "playerId").toString(),
                        required(barId, "barId").toString(), progress));
            }
        }

        @Override
        public LimboControlResult updateBossBarTitle(UUID playerId, UUID barId, String title) {
            synchronized (nativeLock) {
                return result(api.pico_bossbar_title(handle(), required(playerId, "playerId").toString(),
                        required(barId, "barId").toString(), Objects.requireNonNull(title, "title")));
            }
        }

        @Override
        public LimboControlResult removeBossBar(UUID playerId, UUID barId) {
            synchronized (nativeLock) {
                return result(api.pico_bossbar_remove(handle(), required(playerId, "playerId").toString(),
                        required(barId, "barId").toString()));
            }
        }

        @Override
        public LimboControlResult showDialog(UUID playerId, String dialogJson) {
            synchronized (nativeLock) {
                return result(api.pico_dialog_show_json(handle(), required(playerId, "playerId").toString(),
                        Objects.requireNonNull(dialogJson, "dialogJson")));
            }
        }

        @Override
        public LimboControlResult clearDialog(UUID playerId) {
            synchronized (nativeLock) {
                return result(api.pico_dialog_clear(handle(), required(playerId, "playerId").toString()));
            }
        }

        @Override
        public Optional<LimboDialogEvent> pollDialogEvent() {
            synchronized (nativeLock) {
                byte[] buffer = new byte[1024];
                int result = api.pico_dialog_poll_event(handle(), buffer, buffer.length);
                if (result == 0) return Optional.empty();
                if (result < 0) {
                    int required = -result;
                    if (required > 65_536) throw new IllegalStateException("Dialog event exceeds maximum size");
                    buffer = new byte[required];
                    result = api.pico_dialog_poll_event(handle(), buffer, buffer.length);
                }
                if (result < 0) throw new IllegalStateException("Could not read PicoLimbo dialog event");
                String json = new String(buffer, 0, result, StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.JsonNode event;
                try {
                    event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                    return Optional.of(new LimboDialogEvent(
                            UUID.fromString(event.path("playerId").asText()),
                            event.path("actionId").asText(),
                            event.path("data").toString()
                    ));
                } catch (Exception exception) {
                    throw new IllegalStateException("Invalid PicoLimbo dialog event", exception);
                }
            }
        }

        private Pointer handle() {
            Pointer handle = cancellationToken;
            if (handle == null || !running) throw new IllegalStateException("PicoLimbo is not running");
            return handle;
        }

        private UUID required(UUID value, String name) {
            return Objects.requireNonNull(value, name);
        }

        private LimboControlResult result(int code) {
            return LimboControlResult.fromCode(code);
        }
    }

}
