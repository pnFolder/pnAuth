package ru.privatenull.pnauth.limbo

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
class PicoLimboConfig {
    var bind: String? = null
    @JsonProperty("welcome_message") var welcomeMessage: String? = null
    @JsonProperty("action_bar") var actionBar: String? = null
    @JsonProperty("default_game_mode") var defaultGameMode: String? = null
    var hardcore: Boolean = false
    @JsonProperty("fetch_player_skins") var fetchPlayerSkins: Boolean = false
    @JsonProperty("reduced_debug_info") var reducedDebugInfo: Boolean = false
    @JsonProperty("accept_transfers") var acceptTransfers: Boolean = false
    var forwarding: Forwarding = Forwarding()
    var world: World = World()
    @JsonProperty("server_list") var serverList: ServerList = ServerList()
    var connection: Connection = Connection()
    var compression: Compression = Compression()
    @JsonProperty("tab_list") var tabList: TabList = TabList()
    var fly: Fly = Fly()
    @JsonProperty("boss_bar") var bossBar: BossBar = BossBar()
    var title: Title = Title()
    var commands: Commands = Commands()

    fun endpoint(): Endpoint {
        val b = bind
        if (b.isNullOrBlank()) {
            throw IllegalArgumentException("PicoLimbo bind is missing in server.toml")
        }
        val separator = b.lastIndexOf(':')
        if (separator <= 0 || separator == b.length - 1) {
            throw IllegalArgumentException("Invalid PicoLimbo bind: $b")
        }
        return try {
            Endpoint(b.substring(0, separator), b.substring(separator + 1).toInt())
        } catch (exception: NumberFormatException) {
            throw IllegalArgumentException("Invalid PicoLimbo port: $b", exception)
        }
    }

    @JvmRecord
    data class Endpoint(val host: String, val port: Int)

    class Forwarding {
        var method: String = "NONE"
        var secret: String = ""
    }

    class World {
        @JsonProperty("spawn_position") var spawnPosition: List<Double> = listOf(0.0, 320.0, 0.0)
        @JsonProperty("spawn_rotation") var spawnRotation: List<Double> = listOf(0.0, 0.0)
        var dimension: String = "overworld"
        var time: String = "day"
        var experimental: Experimental = Experimental()
        var boundaries: Boundaries = Boundaries()

        class Experimental {
            @JsonProperty("view_distance") var viewDistance: Int = 2
            @JsonProperty("schematic_file") var schematicFile: String = ""
            @JsonProperty("lock_time") var lockTime: Boolean = false
        }

        class Boundaries {
            var enabled: Boolean = true
            @JsonProperty("min_y") var minY: Int = -64
            @JsonProperty("teleport_message") var teleportMessage: String = ""
        }
    }

    class ServerList {
        @JsonProperty("reply_to_status") var replyToStatus: Boolean = false
        @JsonProperty("max_players") var maxPlayers: Int = 20
        @JsonProperty("message_of_the_day") var messageOfTheDay: String = "Authentication lobby"
        @JsonProperty("show_online_player_count") var showOnlinePlayerCount: Boolean = true
        @JsonProperty("server_icon") var serverIcon: String = "server-icon.png"
    }

    class Connection {
        @JsonProperty("keep_alive_interval_seconds") var keepAliveIntervalSeconds: Int = 15
        @JsonProperty("allow_unsupported_versions") var allowUnsupportedVersions: Boolean = false
    }

    class Compression {
        var threshold: Int = -1
        var level: Int = 6
    }

    class TabList {
        var enabled: Boolean = false
        var header: String = ""
        var footer: String = ""
        @JsonProperty("player_listed") var playerListed: Boolean = true
    }

    class Fly {
        @JsonProperty("allow_flight") var allowFlight: Boolean = false
        var flying: Boolean = false
        @JsonProperty("flying_speed") var flyingSpeed: Double = 0.05
    }

    class BossBar {
        var enabled: Boolean = false
        var title: String = ""
        var health: Double = 1.0
        var color: String = "pink"
        var division: Int = 0
    }

    class Title {
        var enabled: Boolean = false
        var title: String = ""
        var subtitle: String = ""
        @JsonProperty("fade_in") var fadeIn: Int = 10
        var stay: Int = 70
        @JsonProperty("fade_out") var fadeOut: Int = 20
    }

    class Commands {
        var spawn: String = ""
        var fly: String = ""
        @JsonProperty("fly_speed") var flySpeed: String = ""
        var transfer: String = ""
    }
}
