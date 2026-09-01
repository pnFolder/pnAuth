package ru.privatenull.pnauth.config

import java.io.IOException
import java.nio.file.Path
import kotlin.jvm.Throws

/**
 * The only entry point for the persistent pnAuth configuration.
 *
 * <p>The YAML schema lives in [PnAuthYamlConfig]; Elytrium Serializer
 * creates the file with comments and defaults. [AuthConfig] is the
 * immutable, validated runtime representation used by the plugin.</p>
 */
class PnAuthConfigManager(file: Path, fallbackJdbcUrl: String?) {
    private val documents = ConfigDocumentStore(file)
    private val fallbackJdbcUrl: String = fallbackJdbcUrl ?: ""

    /** Loads and validates config.yml, creating a documented default on first start. */
    @Throws(IOException::class)
    fun load(): AuthConfig {
        try {
            documents.prepareDirectory()
            if (!documents.exists()) {
                val installed = documents.installDefault()
                if (installed != null) {
                    return AuthConfig.fromYaml(installed, documents.path, fallbackJdbcUrl)
                }
            }

            val snapshot = documents.readStableSnapshot()
            val prepared = ConfigMigrationPipeline.prepareDocument(snapshot)
            return documents.withIsolatedSerializer(prepared) { yaml ->
                ConfigMigrationPipeline.prepareRuntime(yaml)
                AuthConfig.fromYaml(yaml, documents.path, fallbackJdbcUrl)
            }
        } catch (exception: IOException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw IOException(
                "Invalid pnAuth configuration at ${documents.path}: ${exception.message}",
                exception
            )
        }
    }
}
