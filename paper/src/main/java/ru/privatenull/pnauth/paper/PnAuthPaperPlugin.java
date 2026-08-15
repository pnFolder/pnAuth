package ru.privatenull.pnauth.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.config.AuthConfig;
import ru.privatenull.pnauth.kernel.ExtensionKernel;
import ru.privatenull.pnauth.platform.PnPlatform;
import ru.privatenull.pnauth.security.TotpKeyStore;
import ru.privatenull.pnauth.security.TotpService;
import ru.privatenull.pnauth.service.AuthService;
import ru.privatenull.pnauth.storage.JdbcAuthRepository;
import ru.privatenull.pnauth.storage.AuthMigrationService;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.command.AuthCommandService;

import java.nio.file.Path;

/** Paper/Folia bootstrap. All reusable behavior remains in the shared module. */
public final class PnAuthPaperPlugin extends JavaPlugin implements Listener {
    private AuthService auth;
    private PaperPlayerDisplay display;
    private PaperPlatform platform;
    private PaperPlayerDialogs dialogs;
    private AuthMigrationService migration;
    private ru.privatenull.pnauth.config.PaperSettings paperSettings;

    @Override
    public void onEnable() {
        try {
            Path dataFolder = getDataFolder().toPath();
            String defaultUrl = "jdbc:sqlite:" + dataFolder.resolve("auth.db").toAbsolutePath().normalize();
            AuthConfig config = AuthConfig.load(dataFolder.resolve("config.yml"), defaultUrl);
            paperSettings = config.paper();
            JdbcAuthRepository repository = new JdbcAuthRepository(
                    config.storage().url(), config.storage().username(), config.storage().password());

            auth = new AuthService(repository, config.security(), new TotpService(
                    repository, TotpKeyStore.loadOrCreate(dataFolder.resolve("totp.key"))), config.features());
            display = new PaperPlayerDisplay(this);
            dialogs = new PaperPlayerDialogs(this);
            platform = new PaperPlatform(this, display, dialogs);
            auth.installDisplay(display);
            auth.installPlatform(platform);

            AuthMessages messages = AuthMessages.load(
                    dataFolder.resolve("messages"), config.locale(), config.messageFormat());
            migration = new AuthMigrationService(repository);
            AuthCommandService commandService = new AuthCommandService(
                    auth, messages, new PaperAuthActions(this, messages), migration, config.features());
            PaperAuthCommand commandAdapter = new PaperAuthCommand(commandService);
            commandService.definitions().forEach(definition -> {
                var command = getCommand(definition.name());
                if (command == null) {
                    throw new IllegalStateException("Missing command declaration: " + definition.name());
                }
                command.setExecutor(commandAdapter);
                command.setTabCompleter(commandAdapter);
            });

            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(new PaperAccessListener(auth, config.paper()), this);
            getLogger().info("pnAuth enabled for " + platform.type() + ".");
        } catch (Exception exception) {
            throw new IllegalStateException("pnAuth could not be initialized", exception);
        }
    }

    @Override
    public void onDisable() {
        if (display != null) display.close();
        if (dialogs != null) dialogs.close();
        if (auth != null) auth.close();
        if (migration != null) migration.close();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        auth.onJoin(player.getUniqueId(), player.getName(), ip);
        // Teleportation is player-bound and therefore safe on both Paper and Folia.
        // It happens before authentication completes, so the configured movement gate applies immediately.
        tryTeleport(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        auth.onQuit(event.getPlayer().getUniqueId());
    }

    /** Returns the authentication API for plugins which explicitly need auth operations. */
    public AuthApi getApi() { return auth; }

    /** Returns the generic extension kernel. */
    public ExtensionKernel getKernel() { return auth; }

    /** Returns the platform-neutral player API. */
    public PnPlatform getPlatform() { return platform; }

    private void tryTeleport(org.bukkit.entity.Player player) {
        if (paperSettings == null || !paperSettings.teleportEnabled()) return;
        var world = getServer().getWorld(paperSettings.world());
        if (world == null) {
            getLogger().warning("Paper authentication world is not loaded: " + paperSettings.world());
            return;
        }
        var target = new org.bukkit.Location(world, paperSettings.x(), paperSettings.y(), paperSettings.z(),
                paperSettings.yaw(), paperSettings.pitch());
        player.getScheduler().run(this, ignored -> player.teleportAsync(target), null);
    }
}
