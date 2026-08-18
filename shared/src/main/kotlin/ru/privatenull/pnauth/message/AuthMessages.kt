package ru.privatenull.pnauth.message

import ru.privatenull.pnauth.configuration.SafeYaml
import ru.privatenull.pnauth.api.AuthStatus
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.HashMap
import java.util.Locale

class AuthMessages private constructor(
    values: Map<String, Any>,
    val format: MessageFormat
) {
    private val values: Map<String, Any> = java.util.Map.copyOf(values)
    private val renderer: MessageRenderer = MessageRenderers.forFormat(format)

    fun text(key: String): String {
        val value = values[key]
        return renderer.render(value?.toString() ?: key)
    }

    fun text(key: String, replacements: Map<String, String>): String {
        val value = values[key]
        return renderer.render(value?.toString() ?: key, replacements)
    }

    fun lines(key: String): List<String> {
        val value = values[key]
        if (value is List<*>) {
            return value.map { item -> renderer.render(item.toString()) }
        }
        return listOf(text(key))
    }

    fun prompt(status: AuthStatus): String {
        return text("prompt." + status.name.lowercase(Locale.ROOT))
    }

    fun format(): MessageFormat {
        return format
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        @JvmOverloads
        fun load(locale: String, format: MessageFormat? = MessageFormat.LEGACY): AuthMessages {
            return AuthMessages(MessageCatalog.defaults(locale), format ?: MessageFormat.LEGACY)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun load(directory: Path, locale: String, format: MessageFormat?): AuthMessages {
            MessageFileGenerator.ensureAll(directory)
            val file = MessageFileGenerator.ensure(directory, locale)
            val root = SafeYaml.create().load<Any>(Files.readString(file, StandardCharsets.UTF_8))
            val flattened = HashMap<String, Any>(MessageCatalog.defaults(locale))
            flatten("", root, flattened)
            return AuthMessages(flattened, format ?: MessageFormat.LEGACY)
        }

        private fun resource(name: String): InputStream? {
            return AuthMessages::class.java.getResourceAsStream(name)
        }

        private fun flatten(prefix: String, value: Any?, output: MutableMap<String, Any>) {
            if (value is Map<*, *>) {
                for ((k, v) in value) {
                    val key = k.toString()
                    flatten(if (prefix.isEmpty()) key else "$prefix.$key", v, output)
                }
                return
            }
            if (value != null) {
                output[prefix] = value
            }
        }
    }
}
