package ru.privatenull.pnauth.limbo

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.toml.TomlFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern

class PicoLimboConfigStore {

    @Throws(IOException::class)
    fun load(file: Path): PicoLimboConfig {
        if (Files.notExists(file)) {
            throw IOException("Limbo config.toml is missing: $file")
        }
        return TOML.readValue(file.toFile(), PicoLimboConfig::class.java)
    }

    @Throws(IOException::class)
    fun prepareEmbedded(file: Path) {
        prepareEmbedded(file, null, 0)
    }

    @Throws(IOException::class)
    fun prepareEmbedded(file: Path, host: String?, port: Int) {
        val synchronizeEndpoint = !host.isNullOrBlank() && port in 1..65535
        var source = if (Files.exists(file)) Files.readString(file, StandardCharsets.UTF_8) else ""
        if (source.isEmpty() && !synchronizeEndpoint) {
            throw IOException("Limbo config.toml is missing an endpoint: $file")
        }
        if (source.isEmpty() && synchronizeEndpoint) {
            source = "# Managed by pnAuth; additional PicoLimbo settings may be added below.\n" + bindLine(host!!, port) + "\n"
        } else if (synchronizeEndpoint) {
            source = synchronizeBind(source, host!!, port)
        }
        val retained = mutableListOf<String>()
        var inForwardingSection = false
        for (line in source.split("\\R".toRegex())) {
            val trimmed = line.trim()
            if (trimmed.equals("[forwarding]", ignoreCase = true)) {
                inForwardingSection = true
                continue
            }
            if (inForwardingSection && trimmed.startsWith("[")) {
                inForwardingSection = false
            }
            if (inForwardingSection) continue
            if (trimmed.matches("(?i)forwarding\\.(method|secret)\\s*=.*".toRegex())) continue
            retained.add(line)
        }
        while (retained.isNotEmpty() && retained.last().isBlank()) retained.removeAt(retained.size - 1)
        retained.add("")
        retained.add("forwarding.method = 'NONE'")
        retained.add("forwarding.secret = ''")
        retained.add("")
        val updated = retained.joinToString(System.lineSeparator())
        if (updated != source) {
            writeAtomically(file, updated)
        }
    }

    companion object {
        private val TOML = ObjectMapper(TomlFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private fun synchronizeBind(source: String, host: String, port: Int): String {
            val bind = bindLine(host, port)
            val rootBind = Pattern.compile("(?m)^\\s*bind\\s*=.*(?:\\R|$)")
            if (rootBind.matcher(source).find()) {
                return rootBind.matcher(source).replaceFirst(java.util.regex.Matcher.quoteReplacement(bind + System.lineSeparator()))
            }
            return bind + System.lineSeparator() + source
        }

        private fun bindLine(host: String, port: Int): String {
            return "bind = \"" + host.replace("\\", "\\\\").replace("\"", "\\\"") + ":" + port + "\""
        }

        @Throws(IOException::class)
        private fun writeAtomically(file: Path, contents: String) {
            val parent = file.toAbsolutePath().parent
            if (parent != null) Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, file.fileName.toString(), ".tmp")
            try {
                Files.writeString(temporary, contents, StandardCharsets.UTF_8)
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (ignored: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
