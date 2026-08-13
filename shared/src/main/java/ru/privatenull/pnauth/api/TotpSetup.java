package ru.privatenull.pnauth.api;

import java.util.List;

public record TotpSetup(String secret, String provisioningUri, List<String> recoveryCodes) {
    public TotpSetup {
        recoveryCodes = List.copyOf(recoveryCodes);
    }
}
