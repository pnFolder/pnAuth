package ru.privatenull.pnauth.config

import org.yaml.snakeyaml.DumperOptions
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
        var backupCreated = false
        val legacyServerDocument = original?.let(::migrateLegacyServerDocument)
        if (legacyServerDocument != null && original != null) {
            backupBeforeMigration(original)
            backupCreated = true
            Files.writeString(file, legacyServerDocument, StandardCharsets.UTF_8)
        }

        val yaml = PnAuthYamlConfig(file)
        try {
            if (created) yaml.save()
            else yaml.reload()
            migrateProcessingAnimationDefaults(yaml)
            val legacyLimboSource = AuthConfig.migrateLegacyPicoLimboSource(yaml.limbo)
            val config = AuthConfig.fromYaml(yaml, file, fallbackJdbcUrl)
            val needsSchemaWrite = !schemaComplete || yaml.configVersion < AuthConfig.CURRENT_SCHEMA_VERSION ||
                legacyLimboSource || legacyServerDocument != null
            if (needsSchemaWrite && !created && original != null) {
                if (!backupCreated) backupBeforeMigration(original)
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

    /** Converts the v12 single/list routing fields before Elytrium sees the removed keys. */
    private fun migrateLegacyServerDocument(original: ByteArray): String? {
        val parsed = try {
            Yaml().load<Any>(String(original, StandardCharsets.UTF_8)) as? Map<*, *> ?: return null
        } catch (_: RuntimeException) {
            return null
        }
        val rawServers = parsed["servers"] as? Map<*, *> ?: return null
        if (rawServers.containsKey("auth") || rawServers.containsKey("backend")) return null

        val legacyKeys = setOf(
            "auth-server", "auth-servers", "backend-server", "backend-servers",
            "max-players-per-server", "server-limits"
        )
        if (rawServers.keys.none { it?.toString() in legacyKeys }) return null

        val defaultOnline = (rawServers["max-players-per-server"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 100
        val onlineLimits = LinkedHashMap<String, Int>()
        (rawServers["server-limits"] as? Map<*, *>)?.forEach { (key, value) ->
            val name = key?.toString()?.trim().orEmpty()
            val limit = (value as? Number)?.toInt()
            if (name.isNotEmpty() && limit != null && limit > 0) {
                onlineLimits[name.lowercase()] = limit
            }
        }

        fun names(singleKey: String, listKey: String, fallback: String): List<String> {
            val list = (rawServers[listKey] as? Collection<*>)
                ?.mapNotNull { item -> item?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
            if (list.isNotEmpty()) return list.distinctBy { it.lowercase() }
            val single = rawServers[singleKey]?.toString()?.trim().orEmpty()
            return listOf(if (single.isNotEmpty()) single else fallback)
        }

        fun targets(names: List<String>): List<Map<String, Any>> = names.map { name ->
            linkedMapOf(
                "server" to name,
                "online" to (onlineLimits[name.lowercase()] ?: defaultOnline)
            )
        }

        val root = LinkedHashMap<String, Any?>()
        parsed.forEach { (key, value) ->
            if (key != null) root[key.toString()] = value
        }
        val servers = LinkedHashMap<String, Any?>()
        rawServers.forEach { (key, value) ->
            val name = key?.toString() ?: return@forEach
            if (name !in legacyKeys) servers[name] = value
        }
        servers["auth"] = targets(names("auth-server", "auth-servers", "auth"))
        servers["backend"] = targets(names("backend-server", "backend-servers", "hub"))
        root["servers"] = servers

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
        }
        return Yaml(options).dump(root)
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
            "auth:",
            "backend:",
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
