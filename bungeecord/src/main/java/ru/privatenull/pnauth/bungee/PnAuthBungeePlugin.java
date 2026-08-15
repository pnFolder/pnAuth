package ru.privatenull.pnauth.bungee;

import com.github.retrooper.packetevents.PacketEvents;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.command.AuthCommandService;
import ru.privatenull.pnauth.policy.AuthAccessService;
import ru.privatenull.pnauth.config.AuthConfig;
import ru.privatenull.pnauth.config.ProxySettings;
import ru.privatenull.pnauth.service.AuthService;
import ru.privatenull.pnauth.limbo.LimboServer;
import ru.privatenull.pnauth.limbo.LimboServerContext;
import ru.privatenull.pnauth.limbo.LimboServerRegistry;
import ru.privatenull.pnauth.limbo.PicoLimboProvider;
import ru.privatenull.pnauth.security.TotpKeyStore;
import ru.privatenull.pnauth.security.TotpService;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.storage.AuthMigrationService;
import ru.privatenull.pnauth.storage.JdbcAuthRepository;
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator;
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap;

import java.nio.file.Path;
public final class PnAuthBungeePlugin extends Plugin {
    private AuthService auth;
    private BungeeCommandRegistrar commandRegistrar;
    private BungeeAuthListener listener;
    private BungeeDialogListener dialogListener;
    private BungeeAuthTasks authTasks;
    private AuthMigrationService migration;
    private LimboServer limbo;
    private BungeePlayerDisplay playerDisplay;
    private BungeePlatform platform;
    private ru.privatenull.pnauth.dialog.PlayerDialogs playerDialogs;
    private boolean dependencyReady;

    @Override
    public void onLoad() {
        try {
            PacketEventsBootstrap.Result result = PacketEventsBootstrap.ensure(
                    PacketEventsBootstrap.Platform.BUNGEECORD,
                    getDataFolder().toPath(), getDataFolder().toPath().getParent(),
                    message -> getLogger().info(message));
            if (result == PacketEventsBootstrap.Result.INSTALLED_RESTART_REQUIRED) {
                dependencyRestartNotice();
                getProxy().stop("PacketEvents installed by pnAuth; restart the proxy");
                return;
            }
            dependencyReady = PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
            if (!dependencyReady) {
                throw new IllegalStateException("PacketEvents is not loaded; enable auto-install or install it manually");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("pnAuth could not provision PacketEvents", exception);
        }
    }

    @Override
    public void onEnable() {
        if (!dependencyReady) return;
        try {
            Path dataFolder = getDataFolder().toPath();
            String defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize();
            AuthConfig config = AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl);
            ProxySettings proxySettings = config.proxy();
            validateBackendTargets(proxySettings);
            LimboServerRegistry limboRegistry = new LimboServerRegistry();
            limboRegistry.register(new PicoLimboProvider());
            limbo = limboRegistry.create(config.limbo().provider(), new LimboServerContext(dataFolder, config.limbo()));
            if (config.limbo().enabled()) {
                if (!config.proxy().authServer().equalsIgnoreCase(config.limbo().serverName())) {
                    throw new IllegalArgumentException("proxy.auth-server must equal limbo.server-name when limbo is enabled");
                }
                if (config.proxy().backendServer().equalsIgnoreCase(config.limbo().serverName())) {
                    throw new IllegalArgumentException(
                            "servers.backend-server must differ from limbo.server-name; auth and backend cannot share a name");
                }
                try {
                    limbo.start();
                    getProxy().getServers().put(
                            limbo.id(),
                            getProxy().constructServerInfo(limbo.id(),
                                    new java.net.InetSocketAddress(limbo.host(), limbo.port()),
                                    "pnAuth authentication limbo", false)
                    );
                    proxySettings = proxySettings.requiringServerAuth();
                } catch (Exception exception) {
                    limbo.close();
                    limbo = null;
                    throw new IllegalStateException("Embedded PicoLimbo is enabled but could not be started. "
                            + "pnAuth refuses to continue with an unsecured authentication route.", exception);
                }
            }
            JdbcAuthRepository repository = new JdbcAuthRepository(
                    config.storage().url(),
                    config.storage().username(),
                    config.storage().password()
            );
            auth = new AuthService(repository, config.security(), new TotpService(
                    repository, TotpKeyStore.loadOrCreate(dataFolder.resolve("totp.key"))
            ), config.features());
            playerDisplay = new BungeePlayerDisplay(getProxy(), config.messageFormat());
            auth.installDisplay(playerDisplay);
            playerDialogs = new ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs(
                    uniqueId -> getProxy().getPlayer(uniqueId));
            platform = new BungeePlatform(this, playerDisplay, config.messageFormat(), playerDialogs);
            auth.installPlatform(platform);
            AuthMessages messages = AuthMessages.load(dataFolder.resolve("messages"), config.locale(), config.messageFormat());
            BungeeAuthActions actions = new BungeeAuthActions(getProxy(), proxySettings, messages);
            migration = new AuthMigrationService(repository);
            AuthCommandService commandService = new AuthCommandService(auth, messages, actions, migration, config.features());
            AuthAccessService access = new AuthAccessService(auth, proxySettings, config.access(), messages);
            AuthLifecycleCoordinator lifecycle = new AuthLifecycleCoordinator(auth, access);
            commandRegistrar = new BungeeCommandRegistrar(this, getProxy().getPluginManager(), commandService,
                    messages.format());
            commandRegistrar.register();
            dialogListener = new BungeeDialogListener(this, auth, commandService, messages, config.features(),
                    config.security().maxPasswordLength(), proxySettings, platform);
            listener = new BungeeAuthListener(getProxy(), this, lifecycle, messages, commandService,
                    dialogListener);
            authTasks = new BungeeAuthTasks(this, auth, messages, config.features(), proxySettings);
        } catch (Exception exception) {
            throw new IllegalStateException("pnAuth could not be initialized", exception);
        }

        PluginManager pluginManager = getProxy().getPluginManager();
        pluginManager.registerListener(this, listener);
        pluginManager.registerListener(this, dialogListener);
        pluginManager.registerListener(this, authTasks);
        getLogger().info("pnAuth enabled for BungeeCord.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            getProxy().getPluginManager().unregisterListener(listener);
        }
        if (dialogListener != null) {
            getProxy().getPluginManager().unregisterListener(dialogListener);
            dialogListener.close();
        }
        if (authTasks != null) {
            getProxy().getPluginManager().unregisterListener(authTasks);
            authTasks.close();
        }
        if (commandRegistrar != null) {
            commandRegistrar.close();
        }
        if (auth != null) {
            auth.close();
        }
        if (playerDisplay != null) playerDisplay.close();
        if (playerDialogs instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                getLogger().warning("Could not close the pnAuth PacketEvents adapter: " + exception.getMessage());
            }
        }
        if (migration != null) {
            migration.close();
        }
        if (limbo != null) {
            getProxy().getServers().remove(limbo.id());
            limbo.close();
        }
    }

    public AuthApi getApi() {
        return auth;
    }

    public ru.privatenull.pnauth.kernel.ExtensionKernel getKernel() {
        return auth;
    }

    /** Returns the platform-neutral player API. */
    public ru.privatenull.pnauth.platform.PnPlatform getPlatform() {
        return platform;
    }

    private void dependencyRestartNotice() {
        getLogger().warning("============================================================");
        getLogger().warning(" pnAuth FIRST-RUN SETUP");
        getLogger().warning(" PacketEvents was downloaded and SHA-256 verified successfully.");
        getLogger().warning(" The proxy is stopping intentionally so BungeeCord can load it.");
        getLogger().warning(" START THE PROXY ONE MORE TIME to finish enabling pnAuth.");
        getLogger().warning(" Automatic process restart requires an external server wrapper.");
        getLogger().warning(" Settings: plugins/pnAuth/dependencies.yml");
        getLogger().warning("============================================================");
    }

    private void validateBackendTargets(ProxySettings settings) {
        java.util.Set<String> targets = new java.util.LinkedHashSet<>(settings.forcedHosts().values());
        if (settings.hasBackendServer()) targets.add(settings.backendServer());
        for (String target : targets) {
            if (getProxy().getServerInfo(target) == null) {
                throw new IllegalArgumentException("Unknown backend server '" + target
                        + "'; register it in BungeeCord config before enabling pnAuth");
            }
        }
    }

}
