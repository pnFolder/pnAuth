package ru.privatenull.pnauth.storage;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import ru.privatenull.pnauth.api.DialogPreference;

public interface AuthRepository extends AutoCloseable {
    Optional<AuthRecord> findByUniqueId(UUID uniqueId);

    Optional<AuthRecord> findByUsername(String username);

    boolean create(AuthRecord record);

    boolean updateUsername(UUID uniqueId, String username);

    /** Moves an account to the UUID currently assigned by the proxy. */
    default boolean reassignUniqueId(UUID previousUniqueId, UUID currentUniqueId) {
        return previousUniqueId.equals(currentUniqueId);
    }

    void updateLastLogin(UUID uniqueId, long timestamp);

    void updatePassword(UUID uniqueId, PasswordHash passwordHash);

    default void updateLastIp(UUID uniqueId, String ip) {
    }

    default void updatePremium(UUID uniqueId, boolean premium) {
    }

    default void updateTotpSecret(UUID uniqueId, String encryptedSecret) {
    }

    default void updateDialogPreference(UUID uniqueId, DialogPreference preference) {
    }

    default long countRegisteredIp(String ip) {
        return 0;
    }

    default List<AuthRecord> findAll() {
        return List.of();
    }

    default void deleteByUniqueId(UUID uniqueId) {
    }

    default void clearRecoveryCodes(UUID uniqueId) {
    }

    default void addRecoveryCode(UUID uniqueId, String codeHash) {
    }

    default boolean consumeRecoveryCode(UUID uniqueId, String codeHash) {
        return false;
    }

    /** Replaces the encrypted TOTP secret and recovery-code hashes as one logical operation. */
    default void replaceTotpData(UUID uniqueId, String encryptedSecret, List<String> recoveryCodeHashes) {
        updateTotpSecret(uniqueId, encryptedSecret);
        clearRecoveryCodes(uniqueId);
        recoveryCodeHashes.forEach(thisCodeHash -> addRecoveryCode(uniqueId, thisCodeHash));
    }

    /** Removes the encrypted TOTP secret and all recovery-code hashes as one logical operation. */
    default void clearTotpData(UUID uniqueId) {
        updateTotpSecret(uniqueId, null);
        clearRecoveryCodes(uniqueId);
    }

    @Override
    default void close() {
    }
}
