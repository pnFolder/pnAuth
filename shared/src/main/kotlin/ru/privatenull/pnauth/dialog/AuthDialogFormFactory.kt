package ru.privatenull.pnauth.dialog

import net.kyori.adventure.text.Component
import java.util.function.Consumer

/** Creates the one canonical login/register form used by every platform adapter. */
object AuthDialogFormFactory {

    enum class Mode { LOGIN, REGISTER }

    @JvmRecord
    data class Content(
        val title: Component,
        val description: Component,
        val notice: Component?,
        val passwordLabel: Component,
        val confirmationLabel: Component?,
        val submitLabel: Component
    )

    @JvmRecord
    data class Credentials(val password: String, val confirmation: String)

    @JvmStatic
    fun create(
        mode: Mode,
        repeatPasswordOnRegistration: Boolean,
        maximumPasswordLength: Int,
        content: Content,
        submit: Consumer<Credentials>,
        malformedResponse: Runnable
    ): DialogForm {
        val confirmationRequired = mode == Mode.REGISTER && repeatPasswordOnRegistration
        val form = DialogForm.builder(content.title)
            .body(content.description, AuthDialogDimensions.BODY_WIDTH)
            .text(
                "password", content.passwordLabel, maximumPasswordLength,
                AuthDialogDimensions.FIELD_WIDTH
            )
            .columns(1)
            .canCloseWithEscape(false)
            .pause(false)
            .afterAction(DialogLayout.AfterAction.CLOSE)
        if (content.notice != null) {
            form.body(content.notice, AuthDialogDimensions.BODY_WIDTH)
        }
        if (confirmationRequired) {
            requireNotNull(content.confirmationLabel) { "Registration confirmation label is required" }
            form.text(
                "confirmation", content.confirmationLabel, maximumPasswordLength,
                AuthDialogDimensions.FIELD_WIDTH
            )
        }
        return form.button(
            content.submitLabel, Component.empty(), AuthDialogDimensions.SUBMIT_BUTTON_WIDTH
        ) { response ->
            val password = response.string("password").orElse(null)
            val confirmation = if (confirmationRequired)
                response.string("confirmation").orElse(null) else password
            if (password == null || confirmation == null) {
                malformedResponse.run()
                return@button
            }
            submit.accept(Credentials(password, confirmation))
        }.build()
    }
}
