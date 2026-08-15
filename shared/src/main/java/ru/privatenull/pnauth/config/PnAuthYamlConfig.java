package ru.privatenull.pnauth.config;

import net.elytrium.serializer.SerializerConfig;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.annotations.NewLine;
import net.elytrium.serializer.annotations.Transient;
import net.elytrium.serializer.language.object.YamlSerializable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PnAuthYamlConfig extends YamlSerializable {
    @Transient
    private static final SerializerConfig SERIALIZER = new SerializerConfig.Builder()
            .setCommentValueIndent(1)
            .build();

    public PnAuthYamlConfig(Path path) {
        super(path, SERIALIZER);
    }

    @Comment(@CommentValue("Schema version. Managed automatically; do not change unless migration notes say otherwise."))
    public int configVersion = AuthConfig.CURRENT_SCHEMA_VERSION;

    @Comment({
            @CommentValue("Language for generated player messages"),
            @CommentValue("Supported values: ru, en")
    })
    public String locale = "ru";
    public Messages messages = new Messages();
    public Database database = new Database();
    public Servers servers = new Servers();
    public Security security = new Security();
    public Validation validation = new Validation();
    public Access access = new Access();
    public Limits limits = new Limits();
    public Features features = new Features();
    public Ui ui = new Ui();
    public Limbo limbo = new Limbo();
    public Paper paper = new Paper();

    @NewLine
    public static final class Messages {
        @Comment({
                @CommentValue("LEGACY, MINI_MESSAGE, JSON or PLAIN"),
                @CommentValue("Built-in translations and messages_<locale>.yml use this format")
        })
        public String format = "LEGACY";
    }

    @NewLine
    public static final class Database {
        @Comment({
                @CommentValue("SQLITE, H2, MYSQL, MARIADB, POSTGRESQL or JDBC"),
                @CommentValue("For a network use MYSQL, MARIADB or POSTGRESQL")
        })
        public String type = "SQLITE";
        public String file = "auth.db";
        public String url = "";
        public String username = "";
        public String password = "";
        public Connection mysql = new Connection(3306);
        public Connection postgresql = new Connection(5432);
    }

    public static final class Connection {
        public String host = "127.0.0.1";
        public int port;
        public String database = "minecraft_auth";
        public String username = "";
        public String password = "";
        @Comment(@CommentValue("Use and verify TLS for a remote SQL connection. Disable only for a local trusted database."))
        public boolean useSsl = true;
        public String serverTimezone = "UTC";

        public Connection() {
            this(3306);
        }

        public Connection(int port) {
            this.port = port;
        }
    }

    @NewLine
    public static final class Servers {
        @Comment(@CommentValue("Registered proxy server used before authentication"))
        public String authServer = "auth";
        @Comment(@CommentValue("Server a player joins after successful authentication"))
        public String backendServer = "hub";
        @Comment(@CommentValue("Keep unauthenticated players on auth-server (recommended for every public network)"))
        public boolean requireAuthBeforeServer = true;
        @Comment(@CommentValue("Hostname to backend server mapping"))
        public Map<String, String> forcedHosts = new LinkedHashMap<>();
    }

    @NewLine
    public static final class Security {
        public Password password = new Password();
        public Login login = new Login();
        public Hashing hashing = new Hashing();

        public static final class Password {
            @Comment(@CommentValue("Allowed password length, inclusive"))
            public int minLength = 8;
            public int maxLength = 64;
            public boolean repeatOnRegister = true;
        }

        public static final class Login {
            public int maxAttempts = 5;
            public int lockoutSeconds = 60;
            public boolean banOnFailedLogin = true;
            public int banSeconds = 60;
        }

        public static final class Hashing {
            @Comment({
                    @CommentValue("PBKDF2, BCRYPT or ARGON2"),
                    @CommentValue("PBKDF2 is the portable default")
            })
            public String algorithm = "PBKDF2";
            @Comment(@CommentValue("PBKDF2-HMAC-SHA256 iterations; 600000 is the secure portable default"))
            public int pbkdf2Iterations = 600_000;
            public int bcryptCost = 12;
            public int argon2Iterations = 2;
            public int argon2MemoryKb = 65_536;
            public int argon2Parallelism = 1;
        }
    }

    @NewLine
    public static final class Validation {
        @Comment(@CommentValue("Regular expression applied to the Minecraft nickname"))
        public String usernamePattern = "^[A-Za-z0-9_]{3,16}$";
    }

    @NewLine
    public static final class Access {
        @Comment(@CommentValue("Prevent unauthenticated players from using proxy chat"))
        public boolean blockChat = true;
        @Comment(@CommentValue("Command names allowed before authentication, without leading slash"))
        public List<String> unauthenticatedCommands = List.of(
                "auth", "pnauth", "register", "reg", "login", "l", "logout",
                "changepassword", "changepass", "totp", "2fa", "status"
        );
    }

    @NewLine
    public static final class Limits {
        @Comment(@CommentValue("Maximum simultaneous online accounts from one IP address"))
        public int maxOnlineAccountsPerIp = 10;
        public int maxRegisteredAccountsPerIp = 10;
        public List<String> excludedIps = List.of("127.0.0.1");
    }

    @NewLine
    public static final class Features {
        public Premium premium = new Premium();
        public Session session = new Session();
        public Totp totp = new Totp();
        public Captcha captcha = new Captcha();

        public static final class Premium {
            public boolean enabled = true;
        }

        public static final class Session {
            @Comment({
                    @CommentValue("Restore a password session when the IP address matches the previous login"),
                    @CommentValue("Disabled by default: shared NATs and VPNs make IP-only authentication unsafe")
            })
            public boolean restoreOnSameIp = false;
            @Comment(@CommentValue("Session lifetime after successful authentication"))
            public int lifetimeMinutes = 60;
            @Comment(@CommentValue("Disconnect after this many seconds without authentication"))
            public int timeoutSeconds = 60;
            @Comment({
                    @CommentValue("Seconds between unauthenticated reminders"),
                    @CommentValue("The first reminder is sent after this delay; 0 disables reminders")
            })
            public int reminderSeconds = 10;
        }

        public static final class Totp {
            public boolean enabled = true;
            public int maxAttempts = 3;
            public int lockoutSeconds = 60;
            @Comment(@CommentValue("Seconds allowed to confirm a newly generated 2FA setup"))
            public int setupLifetimeSeconds = 300;
            public String issuer = "Minecraft Server";
            public int recoveryCodes = 16;
        }

        public static final class Captcha {
            @Comment(@CommentValue("Require a one-time clickable challenge before showing the password dialog"))
            public boolean enabled = false;
            public int lifetimeSeconds = 30;
            public int maxAttempts = 3;
        }
    }

    @NewLine
    public static final class Ui {
        public Dialogs dialogs = new Dialogs();
        public boolean title = false;
        public boolean actionbar = false;

        public static final class Dialogs {
            public boolean enabled = true;
            public boolean fallbackToCommands = true;
            public boolean allowPlayerPreference = true;
            public int minClientProtocol = 771;
        }
    }

    @NewLine
    public static final class Limbo {
        @Comment({
                @CommentValue("Provider registered by the platform-independent LimboServerRegistry"),
                @CommentValue("Built-in provider: pico")
        })
        public String provider = "pico";
        public boolean enabled = false;
        public String serverName = "auth";
        public String host = "127.0.0.1";
        public int port = 25_566;
        public boolean autoDownload = true;
        public String downloadBaseUrl = LimboSettings.OFFICIAL_DOWNLOAD_BASE_URL;
        public String downloadSha256 = LimboSettings.OFFICIAL_DOWNLOAD_SHA256;
    }

    @NewLine
    public static final class Paper {
        @Comment(@CommentValue("Standalone Paper/Folia behavior before authentication"))
        public Teleport teleport = new Teleport();
        public Restrictions restrictions = new Restrictions();

        public static final class Teleport {
            public boolean enabled = false;
            public String world = "world";
            public double x = 0.5;
            public double y = 100.0;
            public double z = 0.5;
            public float yaw = 0.0f;
            public float pitch = 0.0f;
        }

        public static final class Restrictions {
            public boolean movement = true;
            public boolean chat = true;
            public boolean commands = true;
            public boolean interaction = true;
            public boolean breaking = true;
            public boolean placing = true;
            public boolean inventory = true;
        }
    }
}
