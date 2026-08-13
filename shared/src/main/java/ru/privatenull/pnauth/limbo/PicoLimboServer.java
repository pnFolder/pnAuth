package ru.privatenull.pnauth.limbo;

import com.sun.jna.Pointer;
import ru.privatenull.pnauth.config.LimboSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PicoLimboServer implements LimboServer {
    private final Path dataDirectory;
    private final LimboSettings settings;
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
            PicoLimboConfig picoConfig = new PicoLimboConfigStore().load(config);
            PicoLimboConfig.Endpoint endpoint = picoConfig.endpoint();
            effectiveHost = endpoint.host();
            effectivePort = endpoint.port();
            if (effectivePort != settings.port()) {
                throw new IOException("Limbo port " + effectivePort
                        + " does not match limbo.port " + settings.port() + " in pnAuth config");
            }
            nativeApi = PicoLimboLibrary.load(dataDirectory, settings);
            cancellationToken = nativeApi.get_cancellation_token();
            String[] arguments = {"pico_limbo_java_wrapper", "--config", config.toString()};
            running = true;
            executor.execute(() -> {
                try {
                    nativeApi.start_app(cancellationToken, arguments.length, arguments);
                } finally {
                    running = false;
                    state = LimboServerState.STOPPED;
                    if (cancellationToken != null) nativeApi.cleanup_token(cancellationToken);
                    cancellationToken = null;
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
        if (nativeApi != null && cancellationToken != null) nativeApi.stop_app(cancellationToken);
        running = false;
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

}
