package ru.privatenull.pnauth.api;

import java.util.concurrent.CompletableFuture;

/** Admission checks used by proxy adapters before routing a player. */
public interface AuthAdmissionApi {
    CompletableFuture<AdmissionDecision> checkAdmission(String username, String ip, int onlineAccountsFromIp);
    CompletableFuture<Boolean> isPremium(String username);
}
