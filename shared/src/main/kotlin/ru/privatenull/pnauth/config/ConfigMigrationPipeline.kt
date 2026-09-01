package ru.privatenull.pnauth.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.charset.StandardCharsets

/** Applies compatibility transformations only to an isolated configuration snapshot. */
internal object ConfigMigrationPipeline {
    fun prepareDocument(original: ByteArray): ByteArray {
        return migrateLegacyServerDocument(original)?.toByteArray(StandardCharsets.UTF_8) ?: original
    }

    fun prepareRuntime(yaml: PnAuthYamlConfig) {
        migrateProcessingAnimationDefaults(yaml)
        AuthConfig.migrateLegacyPicoLimboSource(yaml.limbo)
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

    /** Replaces only known historical presets; user-authored animations remain untouched. */
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
}
