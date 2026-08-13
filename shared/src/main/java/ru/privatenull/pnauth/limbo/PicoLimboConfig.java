package ru.privatenull.pnauth.limbo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.privatenull.pnauth.config.LimboSettings;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class PicoLimboConfig {
    public String bind;
    @JsonProperty("welcome_message") public String welcomeMessage;
    @JsonProperty("action_bar") public String actionBar;
    @JsonProperty("default_game_mode") public String defaultGameMode;
    public boolean hardcore;
    @JsonProperty("fetch_player_skins") public boolean fetchPlayerSkins;
    @JsonProperty("reduced_debug_info") public boolean reducedDebugInfo;
    @JsonProperty("accept_transfers") public boolean acceptTransfers;
    public Forwarding forwarding = new Forwarding();
    public World world = new World();
    @JsonProperty("server_list") public ServerList serverList = new ServerList();
    public Connection connection = new Connection();
    public Compression compression = new Compression();
    @JsonProperty("tab_list") public TabList tabList = new TabList();
    public Fly fly = new Fly();
    @JsonProperty("boss_bar") public BossBar bossBar = new BossBar();
    public Title title = new Title();
    public Commands commands = new Commands();

    public PicoLimboConfig() {
    }

    public Endpoint endpoint() {
        if (bind == null || bind.isBlank()) {
            throw new IllegalArgumentException("PicoLimbo bind is missing in server.toml");
        }
        int separator = bind.lastIndexOf(':');
        if (separator <= 0 || separator == bind.length() - 1) {
            throw new IllegalArgumentException("Invalid PicoLimbo bind: " + bind);
        }
        try {
            return new Endpoint(bind.substring(0, separator), Integer.parseInt(bind.substring(separator + 1)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid PicoLimbo port: " + bind, exception);
        }
    }

    public record Endpoint(String host, int port) {
    }

    public static final class Forwarding {
        public String method = "LEGACY";
        public String secret = "";
    }

    public static final class World {
        @JsonProperty("spawn_position") public List<Double> spawnPosition = List.of(0.0, 320.0, 0.0);
        @JsonProperty("spawn_rotation") public List<Double> spawnRotation = List.of(0.0, 0.0);
        public String dimension = "overworld";
        public String time = "day";
        public Experimental experimental = new Experimental();
        public Boundaries boundaries = new Boundaries();

        public static final class Experimental {
            @JsonProperty("view_distance") public int viewDistance = 2;
            @JsonProperty("schematic_file") public String schematicFile = "";
            @JsonProperty("lock_time") public boolean lockTime;
        }

        public static final class Boundaries {
            public boolean enabled = true;
            @JsonProperty("min_y") public int minY = -64;
            @JsonProperty("teleport_message") public String teleportMessage = "";
        }
    }

    public static final class ServerList {
        @JsonProperty("reply_to_status") public boolean replyToStatus;
        @JsonProperty("max_players") public int maxPlayers = 20;
        @JsonProperty("message_of_the_day") public String messageOfTheDay = "Authentication lobby";
        @JsonProperty("show_online_player_count") public boolean showOnlinePlayerCount = true;
        @JsonProperty("server_icon") public String serverIcon = "server-icon.png";
    }

    public static final class Connection {
        @JsonProperty("keep_alive_interval_seconds") public int keepAliveIntervalSeconds = 15;
        @JsonProperty("allow_unsupported_versions") public boolean allowUnsupportedVersions;
    }

    public static final class Compression {
        public int threshold = -1;
        public int level = 6;
    }

    public static final class TabList {
        public boolean enabled;
        public String header = "";
        public String footer = "";
        @JsonProperty("player_listed") public boolean playerListed = true;
    }

    public static final class Fly {
        @JsonProperty("allow_flight") public boolean allowFlight;
        public boolean flying;
        @JsonProperty("flying_speed") public double flyingSpeed = 0.05;
    }

    public static final class BossBar {
        public boolean enabled;
        public String title = "";
        public double health = 1.0;
        public String color = "pink";
        public int division;
    }

    public static final class Title {
        public boolean enabled;
        public String title = "";
        public String subtitle = "";
        @JsonProperty("fade_in") public int fadeIn = 10;
        public int stay = 70;
        @JsonProperty("fade_out") public int fadeOut = 20;
    }

    public static final class Commands {
        public String spawn = "";
        public String fly = "";
        @JsonProperty("fly_speed") public String flySpeed = "";
        public String transfer = "";
    }
}
