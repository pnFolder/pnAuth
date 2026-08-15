package ru.privatenull.pnauth.command;

/** Safe command-root parsing for proxy command events. */
public final class CommandRoots {
    private CommandRoots() {
    }

    /**
     * Returns whether {@code rawCommand} begins with exactly {@code expectedRoot}.
     * Both Bungee and Velocity expose command events slightly differently: Bungee
     * includes the leading slash while Velocity normally does not.
     */
    public static boolean isExactRoot(String rawCommand, String expectedRoot) {
        String root = root(rawCommand);
        return root != null && expectedRoot != null && !expectedRoot.isBlank()
                && root.equalsIgnoreCase(expectedRoot);
    }

    /** True for the player password commands that must be captcha-gated before authentication. */
    public static boolean isPasswordAuthenticationCommand(String rawCommand) {
        String command = normalizedCommand(rawCommand);
        if (command == null) {
            return false;
        }
        int separator = firstWhitespace(command);
        String root = separator < 0 ? command : command.substring(0, separator);
        if (root.equalsIgnoreCase("login") || root.equalsIgnoreCase("l")
                || root.equalsIgnoreCase("register") || root.equalsIgnoreCase("reg")) {
            return true;
        }
        if (!root.equalsIgnoreCase("auth") && !root.equalsIgnoreCase("pnauth")) return false;
        if (separator < 0) return false;
        String remainder = command.substring(separator).trim();
        String subcommand = root(remainder);
        return subcommand != null && (subcommand.equalsIgnoreCase("login") || subcommand.equalsIgnoreCase("l"));
    }

    private static String root(String rawCommand) {
        String command = normalizedCommand(rawCommand);
        if (command == null) return null;
        int separator = firstWhitespace(command);
        return separator < 0 ? command : command.substring(0, separator);
    }

    private static String normalizedCommand(String rawCommand) {
        if (rawCommand == null) return null;
        String command = rawCommand.trim();
        if (command.startsWith("/")) command = command.substring(1);
        return command.isEmpty() || command.startsWith("/") ? null : command;
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
