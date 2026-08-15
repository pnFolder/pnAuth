package ru.privatenull.pnauth.extension;
import java.util.concurrent.CompletionStage;
@FunctionalInterface public interface AuthPolicyHook {
    CompletionStage<AuthPolicyDecision> before(AuthOperationContext context);
}
