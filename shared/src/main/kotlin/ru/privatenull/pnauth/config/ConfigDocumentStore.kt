package ru.privatenull.pnauth.config

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Owns all filesystem access for config.yml.
 *
 * Existing files are immutable from pnAuth's point of view. Serializer code is only ever given a
 * disposable copy. The sole persistent write is an atomic, create-only installation on first start.
 */
internal class ConfigDocumentStore(file: Path) {
    val path: Path = file.toAbsolutePath().normalize()
    private val parent: Path = path.parent
        ?: throw IllegalArgumentException("Configuration path must have a parent directory: $path")

    fun prepareDirectory() {
        Files.createDirectories(parent)
    }

    fun exists(): Boolean = Files.exists(path)

    /**
     * Installs the documented default without overwriting a file created by an administrator or by
     * another plugin instance racing this one. Returns the generated model only when installation won.
     */
    fun installDefault(): PnAuthYamlConfig? {
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".create.tmp")
        try {
            val yaml = PnAuthYamlConfig(temporary)
            yaml.save()
            try {
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, path)
                }
            } catch (_: FileAlreadyExistsException) {
                return null
            }
            return yaml
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Reads a stable snapshot and never exposes the live path to the serializer. */
    fun readStableSnapshot(): ByteArray {
        var previous: ByteArray? = null
        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val first = Files.readAllBytes(path)
            val second = Files.readAllBytes(path)
            if (first.contentEquals(second)) return first
            previous = second
            Thread.yield()
        }
        throw IOException(
            "Configuration changed repeatedly while it was being loaded: $path. " +
                "Finish editing the file and reload pnAuth again (last observed size: ${previous?.size ?: 0} bytes)."
        )
    }

    fun <T> withIsolatedSerializer(document: ByteArray, action: (PnAuthYamlConfig) -> T): T {
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".read.tmp")
        try {
            Files.write(temporary, document)
            val yaml = PnAuthYamlConfig(temporary)
            yaml.reload()
            return action(yaml)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_SNAPSHOT_ATTEMPTS = 3
    }
}
