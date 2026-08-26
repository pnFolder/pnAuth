package ru.privatenull.pnauth.policy

import java.util.Locale

class AccessSettings @JvmOverloads constructor(
    val blockChat: Boolean,
    unauthenticatedCommands: Set<String>? = emptySet()
) {
    val unauthenticatedCommands: Set<String> = normalize(unauthenticatedCommands)

    fun blockChat(): Boolean = blockChat
    fun unauthenticatedCommands(): Set<String> = unauthenticatedCommands

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccessSettings) return false
        return blockChat == other.blockChat && unauthenticatedCommands == other.unauthenticatedCommands
    }

    override fun hashCode(): Int {
        return 31 * blockChat.hashCode() + unauthenticatedCommands.hashCode()
    }

    override fun toString(): String {
        return "AccessSettings(blockChat=$blockChat, unauthenticatedCommands=$unauthenticatedCommands)"
    }

    companion object {
        @JvmStatic
        fun defaults(): AccessSettings = AccessSettings(
            true,
            setOf(
                "auth", "pnauth", "register", "reg", "login", "l", "logout",
                "changepassword", "changepass", "totp", "2fa", "status"
            )
        )

        @JvmStatic
        fun normalize(commands: Set<String>?): Set<String> {
            if (commands == null) return emptySet()
            val normalized = LinkedHashSet<String>()
            for (command in commands) {
                if (command.isNotBlank()) {
                    normalized.add(command.trim().lowercase(Locale.ROOT).replaceFirst("^/".toRegex(), ""))
                }
            }
            return java.util.Set.copyOf(normalized)
        }
    }
}
