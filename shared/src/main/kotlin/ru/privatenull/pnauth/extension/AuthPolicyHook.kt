package ru.privatenull.pnauth.extension

import java.util.concurrent.CompletionStage

fun interface AuthPolicyHook {
    fun before(context: AuthOperationContext): CompletionStage<AuthPolicyDecision>
}
