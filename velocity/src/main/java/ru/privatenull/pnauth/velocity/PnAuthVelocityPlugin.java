package ru.privatenull.pnauth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;
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

import java.nio.file.Path;

@Plugin(
        id = "pnauth",
        name = "pnAuth",
        version = "1.0.0",
        authors = {"privatenull"}
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

    @Inject
    public PnAuthVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            String defaultUrl = "jdbc:sqlite:" + dataDirectory.resolve("auth.db").toAbsolutePath().normalize();
            AuthConfig config = AuthConfig.load(dataDirectory.resolve("config.yml"), defaultUrl);
            ProxySettings proxySettings = config.proxy();
            LimboServerRegistry limboRegistry = new LimboServerRegistry();
            limboRegistry.register(new PicoLimboProvider());
            limbo = limboRegistry.create(config.limbo().provider(), new LimboServerContext(dataDirectory, config.limbo()));
            if (config.limbo().enabled()) {
                if (!config.proxy().authServer().equalsIgnoreCase(config.limbo().serverName())) {
                    throw new IllegalArgumentException("proxy.auth-server must equal limbo.server-name when limbo is enabled");
                }
                try {
                    limbo.start();
                    limboServer = proxy.registerServer(new ServerInfo(
                            limbo.id(), new java.net.InetSocketAddress(limbo.host(), limbo.port())
                    ));
                    proxySettings = proxySettings.requiringServerAuth();
                } catch (Exception exception) {
                    logger.warn("PicoLimbo could not be started; continuing without an embedded limbo server", exception);
                    limbo.close();
                    limbo = null;
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
            messages = AuthMessages.load(dataDirectory.resolve("messages"), config.locale(), config.messageFormat());
            actions = new VelocityAuthActions(proxy, proxySettings, messages, config.messageFormat());
            migration = new AuthMigrationService(repository);
            AuthCommandService commandService = new AuthCommandService(auth, messages, actions, migration, config.features());
            AuthAccessService access = new AuthAccessService(auth, proxySettings, config.access(), messages);
            commandRegistrar = new VelocityCommandRegistrar(proxy, commandService, config.messageFormat());
            commandRegistrar.register();
            proxy.getEventManager().register(this, new VelocityAuthListener(proxy, auth, access, config.messageFormat()));
            authTasks = new VelocityAuthTasks(this, proxy, auth, messages, config.features(), proxySettings,
                    config.messageFormat());
            proxy.getEventManager().register(this, authTasks);
        } catch (Exception exception) {
            logger.error("pnAuth could not be initialized", exception);
            throw new IllegalStateException("pnAuth could not be initialized", exception);
        }

        logger.info("pnAuth enabled for Velocity.");
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (auth == null) {
            return;
        }
        auth.onJoin(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(),
                event.getPlayer().getRemoteAddress().getAddress().getHostAddress()).thenAccept(status ->
                event.getPlayer().sendMessage(VelocityMessages.component(messages.prompt(status), messages.format()))
        );
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (auth != null) {
            auth.onQuit(event.getPlayer().getUniqueId());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (auth != null) {
            auth.close();
        }
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
    }

    public AuthApi getApi() {
        return auth;
    }

}
