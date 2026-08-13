package ru.privatenull.pnauth.command;

import ru.privatenull.pnauth.api.AuthApi;
import ru.privatenull.pnauth.api.AuthResult;
import ru.privatenull.pnauth.api.AuthStatus;
import ru.privatenull.pnauth.api.DialogPreference;
import ru.privatenull.pnauth.api.TotpSetup;
import ru.privatenull.pnauth.message.AuthMessages;
import ru.privatenull.pnauth.storage.AuthMigrationService;
import ru.privatenull.pnauth.config.FeatureSettings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Platform-independent command implementation used by both proxy adapters. */
public final class AuthCommandService implements CommandService {
    private final AuthApi api;
    private final AuthMessages messages;
    private final AuthPlatformBridge actions;
    private final AuthMigrationService migration;
    private final FeatureSettings features;

    public AuthCommandService(AuthApi api, AuthMessages messages) {
        this(api, messages, AuthPlatformBridge.NONE);
    }

    public AuthCommandService(AuthApi api, AuthMessages messages, AuthPlatformBridge actions) {
        this(api, messages, actions, null);
    }

    public AuthCommandService(AuthApi api, AuthMessages messages, AuthPlatformBridge actions,
                              AuthMigrationService migration) {
        this(api, messages, actions, migration, FeatureSettings.defaults());
    }

    public AuthCommandService(AuthApi api, AuthMessages messages, AuthPlatformBridge actions,
                              AuthMigrationService migration, FeatureSettings features) {
        this.api = api;
        this.messages = messages;
        this.actions = actions == null ? AuthPlatformBridge.NONE : actions;
        this.migration = migration;
        this.features = features;
    }

    @Override
    public List<CommandSpec> definitions() {
        return List.of(
                new CommandSpec("auth", List.of("pnauth")),
                new CommandSpec("register", List.of("reg")),
                new CommandSpec("login", List.of("l")),
                new CommandSpec("logout", List.of()),
                new CommandSpec("changepassword", List.of("changepass")),
                new CommandSpec("totp", List.of("2fa")),
                new CommandSpec("unregister", List.of("unreg")),
                new CommandSpec("premium", List.of()),
                new CommandSpec("status", List.of())
        );
    }

    public List<CommandSpec> commands() {
        return definitions();
    }

    @Override
    public CompletionStage<List<String>> execute(CommandContext context) {
        CommandSource source = context.source();
        return execute(new AuthCommandRequest(
                source.uniqueId(), source.username(), context.command(), context.arguments(), source::hasPermission
        ));
    }

    public CompletionStage<List<String>> execute(AuthCommandRequest request) {
        String command = normalize(request.command());
        List<String> args = request.arguments();
        boolean adminRoot = command.equals("auth") || command.equals("pnauth");
        if (command.equals("auth") || command.equals("pnauth")) {
            if (args.isEmpty()) {
                return completed(request.isPlayer() ? help() : adminHelp(request));
            }
            command = normalize(args.get(0));
            args = args.subList(1, args.size());
        }

        if (adminRoot && isAdminCommand(command)) {
            return executeAdmin(request, command, args);
        }
        if (!request.isPlayer()) {
            return completed(messages.text("only-player"));
        }

        UUID uniqueId = request.uniqueId();
        String username = request.username();
        CompletionStage<AuthResult> operation;
        switch (command) {
            case "register", "reg" -> {
                if (args.size() != 2) {
                    return completed(messages.text("usage.register"));
                }
                operation = api.register(uniqueId, username, args.get(0), args.get(1));
            }
            case "login", "l" -> {
                if (args.size() != 1) {
                    return completed(messages.text("usage.login"));
                }
                operation = api.login(uniqueId, args.get(0));
            }
            case "logout" -> {
                if (!args.isEmpty()) {
                    return completed(messages.text("usage.logout"));
                }
                operation = api.logout(uniqueId);
            }
            case "changepassword", "changepass" -> {
                if (args.size() != 2) {
                    return completed(messages.text("usage.changepassword"));
                }
                operation = api.changePassword(uniqueId, args.get(0), args.get(1));
            }
            case "unregister", "unreg" -> {
                if (args.size() != 1) return completed(messages.text("usage.unregister"));
                operation = api.unregister(uniqueId, args.get(0));
            }
            case "premium" -> {
                if (!args.isEmpty()) return completed(messages.text("usage.premium"));
                operation = api.togglePremium(uniqueId);
            }
            case "ui", "dialogs" -> {
                if (args.size() != 1) return completed(messages.text("usage.ui"));
                DialogPreference preference = switch (normalize(args.get(0))) {
                    case "auto" -> DialogPreference.AUTO;
                    case "on", "enable", "enabled" -> DialogPreference.ENABLED;
                    case "off", "disable", "disabled", "command", "commands" -> DialogPreference.DISABLED;
                    default -> null;
                };
                if (preference == null) return completed(messages.text("usage.ui"));
                operation = api.setDialogPreference(uniqueId, preference);
            }
            case "totp", "2fa" -> {
                if (args.isEmpty()) return completed(messages.text("usage.totp"));
                String action = normalize(args.get(0));
                if (action.equals("enable")) {
                    String password = args.size() > 1 ? args.get(1) : null;
                    return api.beginTotpSetup(uniqueId, password, features.totpIssuer())
                            .thenApply(this::totpSetupMessages)
                            .exceptionally(error -> List.of(messages.text("operation-error")));
                }
                if (action.equals("verify") && args.size() == 2) {
                    operation = api.verifyTotp(uniqueId, args.get(1));
                } else if (action.equals("disable") && args.size() == 2) {
                    operation = api.disableTotp(uniqueId, null, args.get(1));
                } else {
                    return completed(messages.text("usage.totp"));
                }
            }
            case "status" -> {
                if (!args.isEmpty()) {
                    return completed(messages.text("usage.status"));
                }
                return completed(statusMessage(api.status(uniqueId)));
            }
            default -> {
                return completed(help());
            }
        }

        String executedCommand = command;
        return operation.handle((result, error) -> {
            if (error != null) return List.of(messages.text("operation-error"));
            if (result == AuthResult.SUCCESS) {
                if (api.isAuthenticated(uniqueId)) {
                    actions.authenticated(uniqueId);
                } else if (executedCommand.equals("logout")) {
                    actions.loggedOut(uniqueId);
                } else if (executedCommand.equals("unregister") || executedCommand.equals("unreg")) {
                    actions.accountDeleted(uniqueId);
                }
            }
            return List.of(resultMessage(result));
        });
    }

    @Override
    public List<String> suggest(CommandContext context) {
        return suggest(context.command(), context.arguments());
    }

    public List<String> suggest(String command, List<String> arguments) {
        String normalized = normalize(command);
        if ((normalized.equals("auth") || normalized.equals("pnauth")) && arguments.isEmpty()) {
            return List.of("unregister", "changepassword", "forcelogin", "forceregister", "forcepremium", "migrate");
        }
        if (normalized.equals("auth") || normalized.equals("pnauth")) {
            String prefix = arguments.isEmpty() ? "" : normalize(arguments.get(arguments.size() - 1));
            return List.of("unregister", "changepassword", "forcelogin", "forceregister", "forcepremium", "migrate").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    public String prompt(AuthStatus status) {
        return messages.prompt(status);
    }

    private String resultMessage(AuthResult result) {
        return messages.text("result." + result.name().toLowerCase(Locale.ROOT));
    }

    private String statusMessage(AuthStatus status) {
        return messages.text("status." + status.name().toLowerCase(Locale.ROOT));
    }

    private List<String> help() {
        return messages.lines("help");
    }

    private List<String> totpSetupMessages(TotpSetup setup) {
        return List.of(
                messages.text("totp.secret", java.util.Map.of("secret", setup.secret())),
                messages.text("totp.uri", java.util.Map.of("uri", setup.provisioningUri())),
                messages.text("totp.recovery", java.util.Map.of("codes", String.join(", ", setup.recoveryCodes()))),
                messages.text("totp.confirm")
        );
    }

    private CompletionStage<List<String>> executeAdmin(AuthCommandRequest request, String command, List<String> args) {
        String permission = "pnauth.admin.commands." + switch (command) {
            case "unregister", "unreg" -> "unregister";
            case "changepassword", "changepass" -> "changepassword";
            case "forcelogin" -> "forcelogin";
            case "forceregister", "forcereg", "register" -> "forceregister";
            case "forcepremium", "premium" -> "forcepremium";
            default -> command;
        };
        if (!request.hasPermission(permission)) {
            return completed(messages.text("no-permission"));
        }
        if (command.equals("unregister") || command.equals("unreg")) {
            if (args.size() != 1) return completed(messages.text("admin.commands.unregister"));
            return api.unregister(args.get(0)).thenApply(result -> List.of(adminResult("unregister", result, args.get(0))));
        }
        if (command.equals("changepassword") || command.equals("changepass")) {
            if (args.size() != 2) return completed(messages.text("admin.commands.changepassword"));
            return api.adminChangePassword(args.get(0), args.get(1))
                    .thenApply(result -> List.of(adminResult("changepassword", result, args.get(0))));
        }
        if (command.equals("forcelogin")) {
            if (args.size() != 1) return completed(messages.text("admin.commands.forcelogin"));
            return api.forceLogin(args.get(0)).thenApply(result -> List.of(adminResult("forcelogin", result, args.get(0))));
        }
        if (command.equals("forcepremium") || command.equals("premium")) {
            if (args.size() != 1) return completed(messages.text("admin.commands.forcepremium"));
            return api.togglePremium(args.get(0)).thenApply(result -> List.of(adminResult("forcepremium", result, args.get(0))));
        }
        if (command.equals("forceregister") || command.equals("forcereg") || command.equals("register")) {
            if (args.size() != 2) return completed(messages.text("admin.commands.forceregister"));
            return api.forceRegister(args.get(0), args.get(1))
                    .thenApply(result -> List.of(adminResult("forceregister", result, args.get(0))));
        }
        if (command.equals("migrate")) {
            if (migration == null || args.size() < 2) return completed(messages.text("admin.commands.migrate"));
            try {
                AuthMigrationService.Source source = AuthMigrationService.Source.valueOf(args.get(0).toUpperCase(Locale.ROOT));
                String user = args.size() > 2 ? args.get(2) : "";
                String password = args.size() > 3 ? args.get(3) : "";
                return migration.migrate(source, args.get(1), user, password)
                        .thenApply(count -> List.of(messages.text("admin.migrate.success", java.util.Map.of("count", String.valueOf(count)))))
                        .exceptionally(error -> List.of(messages.text("admin.migrate.error")));
            } catch (IllegalArgumentException exception) {
                return completed(messages.text("admin.migrate.error"));
            }
        }
        return completed(help());
    }

    private boolean isAdminCommand(String command) {
        return command.equals("unregister") || command.equals("unreg")
                || command.equals("changepassword") || command.equals("changepass")
                || command.equals("forcelogin") || command.equals("forceregister") || command.equals("forcereg")
                || command.equals("forcepremium") || command.equals("premium") || command.equals("register")
                || command.equals("migrate");
    }

    private List<String> adminHelp(AuthCommandRequest request) {
        return List.of(messages.text("admin.usage"));
    }

    private String adminResult(String command, AuthResult result, String player) {
        if (result == AuthResult.SUCCESS) {
            return messages.text("admin." + command + ".success", java.util.Map.of("player", player));
        }
        if (result == AuthResult.PLAYER_NOT_FOUND) return messages.text("player-not-found");
        return resultMessage(result);
    }

    private static CompletionStage<List<String>> completed(String... messages) {
        return CompletableFuture.completedFuture(Arrays.asList(messages));
    }

    private static CompletionStage<List<String>> completed(List<String> messages) {
        return CompletableFuture.completedFuture(messages);
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }
}
