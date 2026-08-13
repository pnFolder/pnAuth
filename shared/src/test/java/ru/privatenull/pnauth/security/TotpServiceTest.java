package ru.privatenull.pnauth.security;

import org.junit.jupiter.api.Test;
import ru.privatenull.pnauth.storage.AuthRecord;
import ru.privatenull.pnauth.storage.AuthRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpServiceTest {
    @Test
    void encryptsSecretsAndCreatesProvisioningData() {
        byte[] key = new byte[32];
        TotpService service = new TotpService(new EmptyRepository(), key);
        String secret = service.generateSecret();
        String encrypted = service.encrypt(secret);

        assertEquals(secret, service.decrypt(encrypted));
        assertTrue(service.provisioningUri("Server", "Steve", secret).startsWith("otpauth://totp/"));
        assertEquals(20, service.generateRecoveryCodes(20).size());
    }

    private static final class EmptyRepository implements AuthRepository {
        @Override
        public Optional<AuthRecord> findByUniqueId(UUID uniqueId) {
            return Optional.empty();
        }

        @Override
        public Optional<AuthRecord> findByUsername(String username) {
            return Optional.empty();
        }

        @Override
        public boolean create(AuthRecord record) {
            return false;
        }

        @Override
        public void updatePassword(UUID uniqueId, ru.privatenull.pnauth.storage.PasswordHash passwordHash) {
        }

        @Override
        public void updateLastLogin(UUID uniqueId, long timestamp) {
        }

        @Override
        public boolean updateUsername(UUID uniqueId, String username) {
            return false;
        }
    }
}
