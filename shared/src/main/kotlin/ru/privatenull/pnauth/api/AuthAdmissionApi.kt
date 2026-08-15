package ru.privatenull.pnauth.api

import java.util.concurrent.CompletableFuture

/** Admission checks used by proxy adapters before routing a player. */
interface AuthAdmissionApi {
    fun checkAdmission(username: String, ip: String, onlineAccountsFromIp: Int): CompletableFuture<AdmissionDecision>
    fun isPremium(username: String): CompletableFuture<Boolean>
}
