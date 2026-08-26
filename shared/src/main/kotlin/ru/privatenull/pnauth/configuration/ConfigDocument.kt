package ru.privatenull.pnauth.configuration

import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.loader.ConfigurationLoader
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path

/** Java-friendly boundary around Configurate; auth-core receives snapshots, not YAML nodes. */
class ConfigDocument private constructor(
    private val path: Path,
    private val loader: ConfigurationLoader<out ConfigurationNode>,
    val node: ConfigurationNode
) {
    /** Writes directly to the configured file. Prefer [saveAtomically] for persistent settings. */
    fun save() = loader.save(node)

    /**
     * Persists a complete YAML document through a sibling temporary file and a replace move.
     * A failed process cannot leave a partially-written configuration at [path].
     */
    fun saveAtomically() {
        val parent = path.parent ?: throw IllegalStateException("Configuration file must have a parent directory: $path")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}", ".tmp")
        try {
            YamlConfigurationLoader.builder().path(temporary).build().save(node)
            moveReplacing(temporary, path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Creates a recoverable byte-for-byte backup before a schema migration. */
    fun backup(suffix: String = ".bak"): Path {
        require(suffix.isNotBlank()) { "Backup suffix must not be blank" }
        val parent = path.parent ?: throw IllegalStateException("Configuration file must have a parent directory: $path")
        Files.createDirectories(parent)
        val backup = path.resolveSibling(path.fileName.toString() + suffix)
        val temporary = Files.createTempFile(parent, ".${path.fileName}", ".backup.tmp")
        try {
            Files.copy(path, temporary, StandardCopyOption.REPLACE_EXISTING)
            moveReplacing(temporary, backup)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return backup
    }

    companion object {
        @JvmStatic
        fun open(path: Path): ConfigDocument {
            val normalized = path.toAbsolutePath().normalize()
            val loader = YamlConfigurationLoader.builder().path(normalized).build()
            return ConfigDocument(normalized, loader, loader.load())
        }

        private fun moveReplacing(source: Path, target: Path) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
