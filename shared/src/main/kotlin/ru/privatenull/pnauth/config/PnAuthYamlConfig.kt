package ru.privatenull.pnauth.config

import net.elytrium.serializer.SerializerConfig
import net.elytrium.serializer.annotations.Comment
import net.elytrium.serializer.annotations.CommentValue
import net.elytrium.serializer.annotations.NewLine
import net.elytrium.serializer.annotations.Transient
import net.elytrium.serializer.language.`object`.YamlSerializable
import java.nio.file.Path

open class PnAuthYamlConfig(path: Path) : YamlSerializable(path, SERIALIZER) {

    @Comment(CommentValue("Schema version. Managed automatically; do not change unless migration notes say otherwise."))
    @JvmField
    var configVersion: Int = AuthConfig.CURRENT_SCHEMA_VERSION

    @Comment(
        CommentValue("Language for generated player messages"),
        CommentValue("Supported values: ru, en")
    )
    @JvmField
    var locale: String = "ru"

    @JvmField
    var messages: Messages = Messages()

    @JvmField
    var database: Database = Database()

    @JvmField
    var servers: Servers = Servers()

    @JvmField
    var security: Security = Security()

    @JvmField
    var validation: Validation = Validation()

    @JvmField
    var access: Access = Access()

    @JvmField
    var limits: Limits = Limits()

    @JvmField
    var features: Features = Features()

    @JvmField
    var ui: Ui = Ui()

    @JvmField
    var limbo: Limbo = Limbo()

    @JvmField
    var paper: Paper = Paper()

    @NewLine
    class Messages {
        @Comment(
            CommentValue("LEGACY, MINI_MESSAGE, JSON or PLAIN"),
            CommentValue("Built-in translations and messages_<locale>.yml use this format")
        )
        @JvmField
        var format: String = "LEGACY"
    }

    @NewLine
    class Database {
        @Comment(
            CommentValue("SQLITE, H2, MYSQL, MARIADB, POSTGRESQL or JDBC"),
            CommentValue("For a network use MYSQL, MARIADB or POSTGRESQL")
        )
        @JvmField
        var type: String = "SQLITE"

        @JvmField
        var file: String = "auth.db"

        @JvmField
        var url: String = ""

        @JvmField
        var username: String = ""

        @JvmField
        var password: String = ""

        @JvmField
        var mysql: Connection = Connection(3306)

        @JvmField
        var postgresql: Connection = Connection(5432)
    }

    class Connection @JvmOverloads constructor(
        @JvmField var port: Int = 3306
    ) {
        @JvmField var host: String = "127.0.0.1"
        @JvmField var database: String = "minecraft_auth"
        @JvmField var username: String = ""
        @JvmField var password: String = ""

        @Comment(CommentValue("Use and verify TLS for a remote SQL connection. Disable only for a local trusted database."))
        @JvmField var useSsl: Boolean = true

        @JvmField var serverTimezone: String = "UTC"
    }

    @NewLine
    class Servers {
        @Comment(CommentValue("Registered proxy server used before authentication"))
        @JvmField var authServer: String = "auth"

        @Comment(CommentValue("Server a player joins after successful authentication"))
        @JvmField var backendServer: String = "hub"

        @Comment(CommentValue("List of target backend servers for load balancing"))
        @JvmField var backendServers: List<String> = ArrayList()

        @Comment(CommentValue("List of target auth servers for load balancing"))
        @JvmField var authServers: List<String> = ArrayList()

        @Comment(CommentValue("Load balancer strategy: LEAST_PLAYERS, FIRST_AVAILABLE, ROUND_ROBIN, RANDOM, FILLING"))
        @JvmField var balancerMode: String = "LEAST_PLAYERS"

        @Comment(CommentValue("Maximum players per server when using FILLING mode"))
        @JvmField var maxPlayersPerServer: Int = 100

        @Comment(CommentValue("Per-server custom max player limits (e.g. hub-small: 50, hub-large: 250)"))
        @JvmField var serverLimits: Map<String, Int> = LinkedHashMap()

        @Comment(CommentValue("Keep unauthenticated players on auth-server (recommended for every public network)"))
        @JvmField var requireAuthBeforeServer: Boolean = true

        @Comment(CommentValue("Hostname to backend server mapping"))
        @JvmField var forcedHosts: Map<String, String> = LinkedHashMap()
    }

    @NewLine
    class Security {
        @JvmField var password: Password = Password()
        @JvmField var login: Login = Login()
        @JvmField var hashing: Hashing = Hashing()

        class Password {
            @Comment(CommentValue("Allowed password length, inclusive"))
            @JvmField var minLength: Int = 8

            @JvmField var maxLength: Int = 64
            @JvmField var repeatOnRegister: Boolean = true
        }

        class Login {
            @JvmField var maxAttempts: Int = 5
            @JvmField var lockoutSeconds: Int = 60
            @JvmField var banOnFailedLogin: Boolean = true
            @JvmField var banSeconds: Int = 60
        }

        class Hashing {
            @Comment(
                CommentValue("PBKDF2, BCRYPT or ARGON2"),
                CommentValue("PBKDF2 is the portable default")
            )
            @JvmField var algorithm: String = "PBKDF2"

            @Comment(CommentValue("PBKDF2-HMAC-SHA256 iterations; 600000 is the secure portable default"))
            @JvmField var pbkdf2Iterations: Int = 600_000

            @JvmField var bcryptCost: Int = 12
            @JvmField var argon2Iterations: Int = 2
            @JvmField var argon2MemoryKb: Int = 65_536
            @JvmField var argon2Parallelism: Int = 1
        }
    }

    @NewLine
    class Validation {
        @Comment(CommentValue("Regular expression applied to the Minecraft nickname"))
        @JvmField var usernamePattern: String = "^[A-Za-z0-9_]{3,16}$"
    }

    @NewLine
    class Access {
        @Comment(CommentValue("Prevent unauthenticated players from using proxy chat"))
        @JvmField var blockChat: Boolean = true

        @Comment(CommentValue("Command names allowed before authentication, without leading slash"))
        @JvmField var unauthenticatedCommands: List<String> = listOf(
            "auth", "pnauth", "register", "reg", "login", "l", "logout",
            "changepassword", "changepass", "totp", "2fa", "status"
        )
    }

    @NewLine
    class Limits {
        @Comment(CommentValue("Maximum simultaneous online accounts from one IP address"))
        @JvmField var maxOnlineAccountsPerIp: Int = 10
        @JvmField var maxRegisteredAccountsPerIp: Int = 10
        @JvmField var excludedIps: List<String> = listOf("127.0.0.1")
    }

    @NewLine
    class Features {
        @JvmField var premium: Premium = Premium()
        @JvmField var session: Session = Session()
        @JvmField var totp: Totp = Totp()
        @JvmField var captcha: Captcha = Captcha()

        class Premium {
            @JvmField var enabled: Boolean = true
        }

        class Session {
            @Comment(
                CommentValue("Restore a password session when the IP address matches the previous login"),
                CommentValue("Disabled by default: shared NATs and VPNs make IP-only authentication unsafe")
            )
            @JvmField var restoreOnSameIp: Boolean = false

            @Comment(CommentValue("Session lifetime after successful authentication"))
            @JvmField var lifetimeMinutes: Int = 60

            @Comment(CommentValue("Disconnect after this many seconds without authentication"))
            @JvmField var timeoutSeconds: Int = 60

            @Comment(
                CommentValue("Seconds between unauthenticated reminders"),
                CommentValue("The first reminder is sent after this delay; 0 disables reminders")
            )
            @JvmField var reminderSeconds: Int = 10
        }

        class Totp {
            @JvmField var enabled: Boolean = true
            @JvmField var maxAttempts: Int = 3
            @JvmField var lockoutSeconds: Int = 60

            @Comment(CommentValue("Seconds allowed to confirm a newly generated 2FA setup"))
            @JvmField var setupLifetimeSeconds: Int = 300

            @JvmField var issuer: String = "Minecraft Server"
            @JvmField var recoveryCodes: Int = 16
        }

        class Captcha {
            @Comment(CommentValue("Require a one-time clickable challenge before showing the password dialog"))
            @JvmField var enabled: Boolean = false
            @JvmField var lifetimeSeconds: Int = 30
            @JvmField var maxAttempts: Int = 3
        }
    }

    @NewLine
    class Ui {
        @JvmField var dialogs: Dialogs = Dialogs()
        @JvmField var processingTitle: ProcessingTitle = ProcessingTitle()
        @JvmField var title: Boolean = false
        @JvmField var actionbar: Boolean = false

        class Dialogs {
            @JvmField var enabled: Boolean = true
            @JvmField var fallbackToCommands: Boolean = true
            @JvmField var allowPlayerPreference: Boolean = true
            @JvmField var minClientProtocol: Int = 771
        }

        class ProcessingTitle {
            @Comment(CommentValue("Show an animated title only while a password is actually being processed"))
            @JvmField var enabled: Boolean = true
            @JvmField var animation: Animation = Animation()
            @JvmField var timings: Timings = Timings()

            class Animation {
                @Comment(CommentValue("NONE or GRADIENT"))
                @JvmField var type: String = "GRADIENT"
                @JvmField var colors: List<String> = listOf("#d8b4fe", "#f0abfc", "#c4b5fd")

                @Comment(CommentValue("Number of gradient positions in one seamless animation cycle"))
                @JvmField var frameCount: Int = 12
            }

            class Timings {
                @JvmField var frameIntervalMillis: Int = 120

                @Comment(CommentValue("Keep the indicator visible for this long even if hashing finishes sooner; authentication is not delayed"))
                @JvmField var minimumDisplayMillis: Int = 2500
                @JvmField var resultFadeInMillis: Int = 0

                @Comment(CommentValue("How long the success or failure title stays before fading out"))
                @JvmField var resultDisplayMillis: Int = 1000
                @JvmField var resultFadeOutMillis: Int = 500
                @JvmField var fadeInMillis: Int = 0
                @JvmField var stayMillis: Int = 5000
                @JvmField var fadeOutMillis: Int = 0
            }
        }
    }

    @NewLine
    class Limbo {
        @Comment(
            CommentValue("Provider registered by the platform-independent LimboServerRegistry"),
            CommentValue("Built-in provider: pico")
        )
        @JvmField var provider: String = "pico"
        @JvmField var enabled: Boolean = false
        @JvmField var serverName: String = "auth"
        @JvmField var host: String = "127.0.0.1"
        @JvmField var port: Int = 25_566
        @JvmField var autoDownload: Boolean = true
        @JvmField var downloadBaseUrl: String = LimboSettings.OFFICIAL_DOWNLOAD_BASE_URL
        @JvmField var downloadSha256: String = LimboSettings.OFFICIAL_DOWNLOAD_SHA256
    }

    @NewLine
    class Paper {
        @Comment(CommentValue("Standalone Paper/Folia behavior before authentication"))
        @JvmField var teleport: Teleport = Teleport()
        @JvmField var restrictions: Restrictions = Restrictions()

        class Teleport {
            @JvmField var enabled: Boolean = false
            @JvmField var world: String = "world"
            @JvmField var x: Double = 0.5
            @JvmField var y: Double = 100.0
            @JvmField var z: Double = 0.5
            @JvmField var yaw: Float = 0.0f
            @JvmField var pitch: Float = 0.0f
        }

        class Restrictions {
            @JvmField var movement: Boolean = true
            @JvmField var chat: Boolean = true
            @JvmField var commands: Boolean = true
            @JvmField var interaction: Boolean = true
            @JvmField var breaking: Boolean = true
            @JvmField var placing: Boolean = true
            @JvmField var inventory: Boolean = true
        }
    }

    companion object {
        @Transient
        private val SERIALIZER: SerializerConfig = SerializerConfig.Builder()
            .setCommentValueIndent(1)
            .build()
    }
}
