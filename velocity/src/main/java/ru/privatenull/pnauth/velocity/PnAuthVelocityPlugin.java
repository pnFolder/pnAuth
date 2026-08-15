package ru.privatenull.pnauth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;
import com.github.retrooper.packetevents.PacketEvents;
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
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.storage.AuthMigrationService;
import ru.privatenull.pnauth.storage.JdbcAuthRepository;
import ru.privatenull.pnauth.velocity.dialog.VelocityAuthFormCoordinator;
import ru.privatenull.pnauth.flow.AuthLifecycleCoordinator;
import ru.privatenull.pnauth.dependency.PacketEventsBootstrap;

import java.nio.file.Path;

@Plugin(
        id = "pnauth",
        name = "pnAuth",
        version = PnAuthBuild.VERSION,
        authors = {"privatenull"},
        dependencies = {@Dependency(id = "packetevents", optional = true)}
)
public final class PnAuthVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private AuthService auth;
    private VelocityCommandRegistrar commandRegistrar;
    private AuthMessages messages;
    private VelocityAuthActions actions;
    private VelocityAuthTasks authTasks;
    private AuthMigrationService migration;
    private LimboServer limbo;
    private RegisteredServer limboServer;
    private VelocityAuthFormCoordinator dialogs;
    private AuthLifecycleCoordinator lifecycle;
    private VelocityPlayerDisplay playerDisplay;
    private VelocityPlatform platform;
    private ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs playerDialogs;

    @Inject
    public PnAuthVelocityPlugin(ProxyServer proxy, Logger logger,
                                @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            PacketEventsBootstrap.Result dependency = PacketEventsBootstrap.ensure(
                    PacketEventsBootstrap.Platform.VELOCITY, dataDirectory, dataDirectory.getParent(),
                    logger::info);
            if (dependency == PacketEventsBootstrap.Result.INSTALLED_RESTART_REQUIRED) {
                dependencyRestartNotice();
                proxy.shutdown(net.kyori.adventure.text.Component.text(
                        "PacketEvents installed by pnAuth; restart the proxy"));
                return;
            }
            if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isLoaded()) {
                throw new IllegalStateException("PacketEvents is not loaded; enable auto-install or install it manually");
            }
            String defaultUrl = "jdbc:sqlite:" + dataDirectory.resolve("auth.db").toAbsolutePath().normalize();
            AuthConfig config = AuthConfig.load(dataDirectory.resolve("config.yml"), defaultUrl);
            ProxySettings proxySettings = config.proxy();
            if (proxySettings.hasBackendServer() && proxy.getServer(proxySettings.backendServer()).isEmpty()) {
                throw new IllegalArgumentException("Unknown servers.backend-server '"
                        + proxySettings.backendServer() + "'; register that server in velocity.toml");
            }
            for (String target : new java.util.LinkedHashSet<>(proxySettings.forcedHosts().values())) {
                if (proxy.getServer(target).isEmpty()) {
                    throw new IllegalArgumentException("Unknown servers.forced-hosts target '"
                            + target + "'; register that server in velocity.toml");
                }
            }
            LimboServerRegistry limboRegistry = new LimboServerRegistry();
            limboRegistry.register(new PicoLimboProvider());
            limbo = limboRegistry.create(config.limbo().provider(), new LimboServerContext(dataDirectory, config.limbo()));
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
                    limboServer = proxy.registerServer(new ServerInfo(
                            limbo.id(), new java.net.InetSocketAddress(limbo.host(), limbo.port())
                    ));
                    logger.info("Registered embedded auth route '{}' at {}:{}; authenticated players route to '{}'.",
                            limbo.id(), limbo.host(), limbo.port(), config.proxy().backendServer());
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
                    repository, TotpKeyStore.loadOrCreate(dataDirectory.resolve("totp.key"))
            ), config.features());
            playerDisplay = new VelocityPlayerDisplay(proxy, config.messageFormat());
            auth.installDisplay(playerDisplay);
            playerDialogs = new ru.privatenull.pnauth.transport.packetevents.PacketEventsPlayerDialogs(
                    uniqueId -> proxy.getPlayer(uniqueId).orElse(null));
            platform = new VelocityPlatform(this, proxy, playerDisplay, config.messageFormat(), playerDialogs);
            auth.installPlatform(platform);
            messages = AuthMessages.load(dataDirectory.resolve("messages"), config.locale(), config.messageFormat());
            actions = new VelocityAuthActions(proxy, proxySettings, messages, config.messageFormat());
            migration = new AuthMigrationService(repository);
            AuthCommandService commandService = new AuthCommandService(auth, messages, actions, migration, config.features());
            dialogs = new VelocityAuthFormCoordinator(proxy, auth, commandService, messages,
                    config.features(), config.messageFormat(), config.security().maxPasswordLength(),
                    proxySettings, platform);
            AuthAccessService access = new AuthAccessService(auth, proxySettings, config.access(), messages);
            lifecycle = new AuthLifecycleCoordinator(auth, access);
            commandRegistrar = new VelocityCommandRegistrar(proxy, commandService, config.messageFormat());
            commandRegistrar.register();
            proxy.getEventManager().register(this, new VelocityAuthListener(
                    proxy, auth, lifecycle, config.messageFormat(), limboServer,
                    proxySettings, dialogs, messages));
            authTasks = new VelocityAuthTasks(this, proxy, auth, messages, config.features(), proxySettings,
                    config.messageFormat(), dialogs);
            proxy.getEventManager().register(this, authTasks);
        } catch (Exception exception) {
            logger.error("pnAuth could not be initialized", exception);
            throw new IllegalStateException("pnAuth could not be initialized", exception);
        }

        logger.info("pnAuth enabled for Velocity.");
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (dialogs != null) dialogs.clearSession(event.getPlayer());
        if (auth != null) {
            lifecycle.quit(event.getPlayer().getUniqueId());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (auth != null) {
            auth.close();
        }
        if (playerDisplay != null) playerDisplay.close();
        if (migration != null) {
            migration.close();
        }
        if (limboServer != null) {
            proxy.unregisterServer(limboServer.getServerInfo());
        }
        if (limbo != null) {
            limbo.close();
        }
        if (commandRegistrar != null) {
            commandRegistrar.close();
        }
        if (authTasks != null) {
            authTasks.close();
        }
        if (dialogs != null) {
            dialogs.close();
        }
        if (playerDialogs != null) {
            playerDialogs.close();
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
        logger.warn("============================================================");
        logger.warn(" pnAuth FIRST-RUN SETUP");
        logger.warn(" PacketEvents was downloaded and SHA-256 verified successfully.");
        logger.warn(" The proxy is stopping intentionally so Velocity can load it.");
        logger.warn(" START THE PROXY ONE MORE TIME to finish enabling pnAuth.");
        logger.warn(" Automatic process restart requires an external server wrapper.");
        logger.warn(" Settings: plugins/pnAuth/dependencies.yml");
        logger.warn("============================================================");
    }

}
