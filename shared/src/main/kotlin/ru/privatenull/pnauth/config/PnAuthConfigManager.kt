package ru.privatenull.pnauth.config

import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.jvm.Throws

/**
 * The only entry point for the persistent pnAuth configuration.
 *
 * <p>The YAML schema lives in [PnAuthYamlConfig]; Elytrium Serializer
 * creates the file with comments and defaults. [AuthConfig] is the
 * immutable, validated runtime representation used by the plugin.</p>
 */
class PnAuthConfigManager(file: Path, fallbackJdbcUrl: String?) {
    private val file: Path = file.toAbsolutePath().normalize()
    private val fallbackJdbcUrl: String = fallbackJdbcUrl ?: ""

    /** Loads and validates config.yml, creating a documented default on first start. */
    @Throws(IOException::class)
    fun load(): AuthConfig {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)

        val created = Files.notExists(file)
        val schemaComplete = !created && hasRequiredSchemaKeys(file)
        val original = if (created) null else Files.readAllBytes(file)
        val yaml = PnAuthYamlConfig(file)
        try {
            if (created) yaml.save()
            else yaml.reload()
            val legacyServerGroups = original?.let { migrateLegacyServerGroups(yaml, it) } ?: false
            migrateProcessingAnimationDefaults(yaml)
            val legacyLimboSource = AuthConfig.migrateLegacyPicoLimboSource(yaml.limbo)
            val config = AuthConfig.fromYaml(yaml, file, fallbackJdbcUrl)
            val needsSchemaWrite = !schemaComplete || yaml.configVersion < AuthConfig.CURRENT_SCHEMA_VERSION ||
                legacyLimboSource || legacyServerGroups
            if (needsSchemaWrite && !created && original != null) {
                backupBeforeMigration(original)
                yaml.configVersion = AuthConfig.CURRENT_SCHEMA_VERSION
                yaml.save()
            }
            return config
        } catch (exception: IOException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException("Invalid pnAuth configuration at $file: ${exception.message}", exception)
        }
    }

    private fun backupBeforeMigration(original: ByteArray) {
        val backup = file.resolveSibling(file.fileName.toString() + ".bak")
        val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".bak.tmp")
        try {
            Files.write(temporary, original)
            try {
                Files.move(temporary, backup, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (ignored: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, backup, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Migrates v12 single/list server fields into v13 structured server + online entries. */
    private fun migrateLegacyServerGroups(yaml: PnAuthYamlConfig, original: ByteArray): Boolean {
        val root = try {
            Yaml().load<Any>(String(original, StandardCharsets.UTF_8)) as? Map<*, *> ?: return false
        } catch (_: RuntimeException) {
            return false
        }
        val servers = root["servers"] as? Map<*, *> ?: return false

        val currentAuth = servers["auth-servers"] as? Collection<*>
        val currentBackend = servers["backend-servers"] as? Collection<*>
        val alreadyStructured = sequenceOf(currentAuth, currentBackend)
            .filterNotNull()
            .flatten()
            .any { it is Map<*, *> && it.containsKey("server") }
        if (alreadyStructured) return false

        val legacyKeys = setOf(
            "auth-server", "auth-servers", "backend-server", "backend-servers",
            "max-players-per-server", "server-limits"
        )
        if (servers.keys.none { it?.toString() in legacyKeys }) return false

        val defaultOnline = (servers["max-players-per-server"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 100
        val onlineLimits = LinkedHashMap<String, Int>()
        val rawLimits = servers["server-limits"] as? Map<*, *>
        rawLimits?.forEach { (key, value) ->
            val name = key?.toString()?.trim().orEmpty()
            val limit = (value as? Number)?.toInt()
            if (name.isNotEmpty() && limit != null && limit > 0) onlineLimits[name.lowercase()] = limit
        }

        fun names(singleKey: String, listKey: String, fallback: String): List<String> {
            val list = (servers[listKey] as? Collection<*>)
                ?.mapNotNull { entry ->
                    when (entry) {
                        is Map<*, *> -> entry["server"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
                        else -> entry?.toString()?.trim()?.takeIf(String::isNotEmpty)
                    }
                }
                .orEmpty()
            if (list.isNotEmpty()) return list.distinctBy { it.lowercase() }
            val single = servers[singleKey]?.toString()?.trim().orEmpty()
            return listOf(if (single.isNotEmpty()) single else fallback)
        }

        fun targets(names: List<String>): List<ServerTarget> = names.map { name ->
            ServerTarget(name, onlineLimits[name.lowercase()] ?: defaultOnline)
        }

        yaml.servers.authServers = targets(names("auth-server", "auth-servers", "auth"))
        yaml.servers.backendServers = targets(names("backend-server", "backend-servers", "hub"))
        return true
    }

    /** Replaces only the short-lived v9 preset; user-authored frame animations remain untouched. */
    private fun migrateProcessingAnimationDefaults(yaml: PnAuthYamlConfig) {
        if (yaml.configVersion >= 12) return
        val animation = yaml.ui.processingTitle.animation
        val timings = yaml.ui.processingTitle.timings
        val oldFrames = listOf(
            "<gradient:#d8b4fe:#f0abfc><bold>{text}</bold></gradient>",
            "<gradient:#f0abfc:#c4b5fd><bold>{text}</bold></gradient>",
            "<gradient:#c4b5fd:#d8b4fe><bold>{text}</bold></gradient>"
        )
        if (animation.type.equals("FRAMES", ignoreCase = true) && animation.frames == oldFrames &&
            timings.frameIntervalTicks == 3
        ) {
            animation.type = "GRADIENT"
            animation.colors = listOf("#8b5cf6", "#c084fc", "#38bdf8")
            animation.frameCount = 18
            timings.frameIntervalTicks = 4
        }
        if (animation.type.equals("GRADIENT", ignoreCase = true) &&
            animation.colors == listOf("#8b5cf6", "#c084fc", "#38bdf8") &&
            animation.frameCount == 18 && timings.frameIntervalTicks == 4
        ) {
            animation.colors = listOf("#7c3aed", "#a855f7", "#ec4899", "#38bdf8", "#7c3aed")
            animation.frameCount = 36
        }
        if (animation.type.equals("GRADIENT", ignoreCase = true) &&
            animation.colors == listOf("#7c3aed", "#a855f7", "#ec4899", "#38bdf8", "#7c3aed") &&
            animation.frameCount == 36 && timings.frameIntervalTicks == 4
        ) {
            animation.colors = listOf("#7c3aed", "#a855f7", "#6366f1", "#38bdf8", "#7c3aed")
            animation.frameCount = 48
            timings.frameIntervalTicks = 2
        }
    }

    companion object {
        private val REQUIRED_SCHEMA_KEYS = listOf(
            "config-version:",
            "auth-servers:",
            "backend-servers:",
            "setup-lifetime-seconds:",
            "restore-on-same-ip:",
            "processing-title:",
            "paper:",
            "external-verification:",
            "cluster:"
        )

        private fun hasRequiredSchemaKeys(file: Path): Boolean {
            val lines = Files.readAllLines(file).map { it.trimStart() }
            return REQUIRED_SCHEMA_KEYS.all { key -> lines.any { line -> line.startsWith(key) } }
        }
    }
}
