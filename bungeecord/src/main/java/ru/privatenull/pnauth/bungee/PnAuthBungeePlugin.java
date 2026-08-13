package ru.privatenull.pnauth.bungee;

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

import java.nio.file.Path;
public final class PnAuthBungeePlugin extends Plugin {
    private AuthService auth;
    private BungeeCommandRegistrar commandRegistrar;
    private BungeeAuthListener listener;
    private BungeeDialogListener dialogListener;
    private BungeeAuthTasks authTasks;
    private AuthMigrationService migration;
    private LimboServer limbo;

    @Override
    public void onEnable() {
        try {
            Path dataFolder = getDataFolder().toPath();
            String defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize();
            AuthConfig config = AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl);
            ProxySettings proxySettings = config.proxy();
            LimboServerRegistry limboRegistry = new LimboServerRegistry();
            limboRegistry.register(new PicoLimboProvider());
            limbo = limboRegistry.create(config.limbo().provider(), new LimboServerContext(dataFolder, config.limbo()));
            if (config.limbo().enabled()) {
                if (!config.proxy().authServer().equalsIgnoreCase(config.limbo().serverName())) {
                    throw new IllegalArgumentException("proxy.auth-server must equal limbo.server-name when limbo is enabled");
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
                    getLogger().log(java.util.logging.Level.WARNING,
                            "PicoLimbo could not be started; continuing without an embedded limbo server", exception);
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
                    repository, TotpKeyStore.loadOrCreate(dataFolder.resolve("totp.key"))
            ), config.features());
            AuthMessages messages = AuthMessages.load(dataFolder.resolve("messages"), config.locale(), config.messageFormat());
            BungeeAuthActions actions = new BungeeAuthActions(getProxy(), proxySettings, messages);
            migration = new AuthMigrationService(repository);
            AuthCommandService commandService = new AuthCommandService(auth, messages, actions, migration, config.features());
            AuthAccessService access = new AuthAccessService(auth, proxySettings, config.access(), messages);
            commandRegistrar = new BungeeCommandRegistrar(this, getProxy().getPluginManager(), commandService,
                    messages.format());
            commandRegistrar.register();
            listener = new BungeeAuthListener(getProxy(), this, auth, access, messages, commandService, actions);
            dialogListener = new BungeeDialogListener(this, auth, commandService, messages, config.features(), proxySettings);
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

}
