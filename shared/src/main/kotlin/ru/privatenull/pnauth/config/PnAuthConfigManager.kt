package ru.privatenull.pnauth.config

import java.io.IOException
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
            migrateProcessingAnimationDefaults(yaml)
            val legacyLimboSource = AuthConfig.migrateLegacyPicoLimboSource(yaml.limbo)
            val config = AuthConfig.fromYaml(yaml, file, fallbackJdbcUrl)
            val needsSchemaWrite = !schemaComplete || yaml.configVersion < AuthConfig.CURRENT_SCHEMA_VERSION || legacyLimboSource
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

    /** Replaces only the short-lived v9 preset; user-authored frame animations remain untouched. */
    private fun migrateProcessingAnimationDefaults(yaml: PnAuthYamlConfig) {
        if (yaml.configVersion >= 10) return
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
    }

    companion object {
        private val REQUIRED_SCHEMA_KEYS = listOf(
            "config-version:",
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
