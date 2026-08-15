package ru.privatenull.pnauth.message

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.Locale

object MessageFileGenerator {

    @JvmStatic
    @Throws(IOException::class)
    fun ensureAll(directory: Path) {
        Files.createDirectories(directory)
        ensure(directory, "ru")
        ensure(directory, "en")
    }

    @JvmStatic
    @Throws(IOException::class)
    fun ensure(directory: Path, locale: String?): Path {
        Files.createDirectories(directory)
        val normalized = normalize(locale)
        val file = directory.resolve("messages_$normalized.yml")
        if (Files.notExists(file)) {
            write(file, MessageCatalog.defaults(normalized))
        } else {
            read(file)
        }
        return file
    }

    private fun read(file: Path): Map<String, Any> {
        val root = Yaml().load<Any>(Files.readString(file, StandardCharsets.UTF_8))
        val flattened = LinkedHashMap<String, Any>()
        if (root == null) return flattened
        if (root !is Map<*, *>) {
            throw IOException("Message file root must be a YAML mapping: $file")
        }
        flatten("", root, flattened)
        return flattened
    }

    private fun write(file: Path, values: Map<String, Any>) {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            defaultScalarStyle = DumperOptions.ScalarStyle.DOUBLE_QUOTED
        }
        val header = "# pnAuth messages are generated from code defaults.\n" +
                "# Edit values below. Existing values are preserved when the plugin is updated.\n" +
                "# Missing keys use built-in defaults without rewriting this file.\n\n"
        val parent = file.parent
        val temporary = Files.createTempFile(parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, header + Yaml(options).dump(unflatten(values)), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (ignored: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun unflatten(values: Map<String, Any>): Map<String, Any> {
        val root = LinkedHashMap<String, Any>()
        for ((keyStr, value) in values) {
            val path = keyStr.split(".")
            var current: MutableMap<String, Any> = root
            for (i in 0 until path.size - 1) {
                var child = current[path[i]]
                if (child !is Map<*, *>) {
                    child = LinkedHashMap<String, Any>()
                    current[path[i]] = child
                }
                current = cast(child)
            }
            current[path[path.size - 1]] = value
        }
        return root
    }

    private fun flatten(prefix: String, value: Any?, output: MutableMap<String, Any>) {
        if (value is Map<*, *>) {
            for ((k, v) in value) {
                val key = k.toString()
                flatten(if (prefix.isEmpty()) key else "$prefix.$key", v, output)
            }
            return
        }
        if (value != null) output[prefix] = value
    }

    @Suppress("UNCHECKED_CAST")
    private fun cast(value: Any): MutableMap<String, Any> {
        return value as MutableMap<String, Any>
    }

    private fun normalize(locale: String?): String {
        val value = if (locale == null) "ru" else locale.trim().lowercase(Locale.ROOT)
        return if (value.matches(Regex("[a-z]{2}"))) value else "ru"
    }
}
