package ru.privatenull.pnauth.dialog

import java.util.Optional

/** Values submitted by a player through a dialog. */
@JvmRecord
data class DialogResponse(val action: String, val values: Map<String, Any>, val closed: Boolean) {
    /**
     * Returns a textual form value. Minecraft may encode text containing only a number or a
     * boolean as a scalar NBT tag, so transports expose those scalar values as their exact text.
     */
    fun string(id: String): Optional<String> {
        return scalarText(values[id])
    }

    fun bool(id: String): Optional<Boolean> {
        val value = values[id]
        if (value is Boolean) return Optional.of(value)
        if (value is Number) return Optional.of(value.toInt() != 0)
        if (value is String) {
            if (value.equals("true", ignoreCase = true) || value == "1") return Optional.of(true)
            if (value.equals("false", ignoreCase = true) || value == "0") return Optional.of(false)
        }
        return Optional.empty()
    }

    fun number(id: String): Optional<Number> {
        val value = values[id]
        if (value is Number) return Optional.of(value)
        if (value is String) {
            try {
                return Optional.of(value.toDouble())
            } catch (ignored: NumberFormatException) {
                return Optional.empty()
            }
        }
        return Optional.empty()
    }

    companion object {
        private fun scalarText(value: Any?): Optional<String> {
            if (value is String) return Optional.of(value)
            if (value is Number || value is Boolean || value is Char) {
                return Optional.of(value.toString())
            }
            if (value is Map<*, *>) {
                val preferredKeys = arrayOf("value", "text", "input", "data", "contents", "result")
                for (preferredKey in preferredKeys) {
                    for ((k, v) in value) {
                        if (preferredKey.equals(k.toString(), ignoreCase = true)) {
                            val preferred = scalarText(v)
                            if (preferred.isPresent) return preferred
                        }
                    }
                }
                var candidate: String? = null
                for ((k, v) in value) {
                    val key = k.toString()
                    if (key.equals("type", ignoreCase = true) || key.equals("kind", ignoreCase = true)
                        || key.equals("id", ignoreCase = true) || key.equals("codec", ignoreCase = true)
                        || key.equals("serializer", ignoreCase = true)
                    ) continue
                    val preferred = scalarText(v)
                    if (preferred.isPresent) {
                        candidate = betterTextCandidate(candidate, preferred.get())
                    }
                }
                return Optional.ofNullable(candidate)
            }
            if (value is Iterable<*>) {
                var candidate: String? = null
                for (entry in value) {
                    val preferred = scalarText(entry)
                    if (preferred.isPresent) {
                        candidate = betterTextCandidate(candidate, preferred.get())
                    }
                }
                return Optional.ofNullable(candidate)
            }
            if (value != null && value.javaClass.isArray) {
                val length = java.lang.reflect.Array.getLength(value)
                var candidate: String? = null
                for (index in 0 until length) {
                    val preferred = scalarText(java.lang.reflect.Array.get(value, index))
                    if (preferred.isPresent) candidate = betterTextCandidate(candidate, preferred.get())
                }
                return Optional.ofNullable(candidate)
            }
            return Optional.empty()
        }

        private fun betterTextCandidate(current: String?, candidate: String): String {
            if (current == null) return candidate
            if (current.isBlank() && candidate.isNotBlank()) return candidate
            if (looksLikeMetadata(current) && !looksLikeMetadata(candidate)) return candidate
            if (looksLikeMetadata(current) == looksLikeMetadata(candidate) && candidate.length > current.length) {
                return candidate
            }
            return current
        }

        private fun looksLikeMetadata(value: String): Boolean {
            return value.startsWith("minecraft:") || value.startsWith("pnauth:")
                    || value.equals("string", ignoreCase = true) || value.equals("text", ignoreCase = true)
        }
    }
}
