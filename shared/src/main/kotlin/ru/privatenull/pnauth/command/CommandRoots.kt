package ru.privatenull.pnauth.command

/** Safe command-root parsing for proxy command events. */
object CommandRoots {
    /**
     * Returns whether [rawCommand] begins with exactly [expectedRoot].
     * Both Bungee and Velocity expose command events slightly differently: Bungee
     * includes the leading slash while Velocity normally does not.
     */
    @JvmStatic
    fun isExactRoot(rawCommand: String?, expectedRoot: String?): Boolean {
        val root = root(rawCommand)
        return root != null && !expectedRoot.isNullOrBlank() && root.equals(expectedRoot, ignoreCase = true)
    }

    /** True for the player password commands that must be captcha-gated before authentication. */
    @JvmStatic
    fun isPasswordAuthenticationCommand(rawCommand: String?): Boolean {
        val command = normalizedCommand(rawCommand) ?: return false
        val separator = firstWhitespace(command)
        val root = if (separator < 0) command else command.substring(0, separator)
        if (root.equals("login", ignoreCase = true) || root.equals("l", ignoreCase = true)
            || root.equals("register", ignoreCase = true) || root.equals("reg", ignoreCase = true)
        ) {
            return true
        }
        if (!root.equals("auth", ignoreCase = true) && !root.equals("pnauth", ignoreCase = true)) return false
        if (separator < 0) return false
        val remainder = command.substring(separator).trim()
        val subcommand = root(remainder)
        return subcommand != null && (subcommand.equals("login", ignoreCase = true) || subcommand.equals("l", ignoreCase = true))
    }

    private fun root(rawCommand: String?): String? {
        val command = normalizedCommand(rawCommand) ?: return null
        val separator = firstWhitespace(command)
        return if (separator < 0) command else command.substring(0, separator)
    }

    private fun normalizedCommand(rawCommand: String?): String? {
        if (rawCommand == null) return null
        var command = rawCommand.trim()
        if (command.startsWith("/")) command = command.substring(1)
        return if (command.isEmpty() || command.startsWith("/")) null else command
    }

    private fun firstWhitespace(value: String): Int {
        for (index in value.indices) {
            if (Character.isWhitespace(value[index])) {
                return index
            }
        }
        return -1
    }
}
