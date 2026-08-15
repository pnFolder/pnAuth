package ru.privatenull.pnauth.dialog;

import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.function.Consumer;

/** Creates the one canonical login/register form used by every platform adapter. */
public final class AuthDialogFormFactory {
    private AuthDialogFormFactory() { }

    public enum Mode { LOGIN, REGISTER }

    public record Content(Component title, Component description, Component notice,
                          Component passwordLabel, Component confirmationLabel,
                          Component submitLabel) {
        public Content {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(passwordLabel, "passwordLabel");
            Objects.requireNonNull(submitLabel, "submitLabel");
        }
    }

    public record Credentials(String password, String confirmation) { }

    public static DialogForm create(Mode mode, boolean repeatPasswordOnRegistration,
                                    int maximumPasswordLength, Content content,
                                    Consumer<Credentials> submit, Runnable malformedResponse) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(submit, "submit");
        Objects.requireNonNull(malformedResponse, "malformedResponse");
        boolean confirmationRequired = mode == Mode.REGISTER && repeatPasswordOnRegistration;
        DialogForm.Builder form = DialogForm.builder(content.title())
                .body(content.description(), AuthDialogDimensions.BODY_WIDTH)
                .text("password", content.passwordLabel(), maximumPasswordLength,
                        AuthDialogDimensions.FIELD_WIDTH)
                .columns(1)
                .canCloseWithEscape(false)
                .pause(false)
                .afterAction(DialogLayout.AfterAction.CLOSE);
        if (content.notice() != null) {
            form.body(content.notice(), AuthDialogDimensions.BODY_WIDTH);
        }
        if (confirmationRequired) {
            if (content.confirmationLabel() == null) {
                throw new IllegalArgumentException("Registration confirmation label is required");
            }
            form.text("confirmation", content.confirmationLabel(), maximumPasswordLength,
                    AuthDialogDimensions.FIELD_WIDTH);
        }
        return form.button(content.submitLabel(), Component.empty(), AuthDialogDimensions.SUBMIT_BUTTON_WIDTH,
                response -> {
                    String password = response.string("password").orElse(null);
                    String confirmation = confirmationRequired
                            ? response.string("confirmation").orElse(null) : password;
                    if (password == null || confirmation == null) {
                        malformedResponse.run();
                        return;
                    }
                    submit.accept(new Credentials(password, confirmation));
                }).build();
    }
}
