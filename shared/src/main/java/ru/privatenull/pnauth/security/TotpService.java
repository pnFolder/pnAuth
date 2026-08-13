package ru.privatenull.pnauth.security;

import ru.privatenull.pnauth.storage.AuthRepository;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class TotpService {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AuthRepository repository;
    private final byte[] encryptionKey;

    public TotpService(AuthRepository repository, byte[] encryptionKey) {
        if (encryptionKey == null || encryptionKey.length != 32) {
            throw new IllegalArgumentException("TOTP encryption key must be 32 bytes");
        }
        this.repository = repository;
        this.encryptionKey = encryptionKey.clone();
    }

    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public boolean verify(String secret, String code) {
        if (secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long time = System.currentTimeMillis() / 30_000L;
        for (long offset = -1; offset <= 1; offset++) {
            if (generateCode(secret, time + offset).equals(code)) {
                return true;
            }
        }
        return false;
    }

    public String provisioningUri(String issuer, String username, String secret) {
        String safeIssuer = issuer == null || issuer.isBlank() ? "pnAuth" : issuer;
        return "otpauth://totp/" + encode(safeIssuer + ":" + username)
                + "?secret=" + secret + "&issuer=" + encode(safeIssuer) + "&algorithm=SHA1&digits=6&period=30";
    }

    public List<String> generateRecoveryCodes(int amount) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            StringBuilder code = new StringBuilder();
            for (int part = 0; part < 4; part++) {
                if (part > 0) code.append('-');
                for (int character = 0; character < 4; character++) {
                    code.append("ABCDEFGHJKLMNPQRSTUVWXYZ23456789".charAt(RANDOM.nextInt(32)));
                }
            }
            codes.add(code.toString());
        }
        return codes;
    }

    public void saveRecoveryCodes(UUID uniqueId, List<String> codes) {
        repository.clearRecoveryCodes(uniqueId);
        codes.forEach(code -> repository.addRecoveryCode(uniqueId, hashRecoveryCode(code)));
    }

    public boolean consumeRecoveryCode(UUID uniqueId, String code) {
        return repository.consumeRecoveryCode(uniqueId, hashRecoveryCode(code));
    }

    public String encrypt(String secret) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt TOTP secret", exception);
        }
    }

    public String decrypt(String encrypted) {
        try {
            byte[] data = Base64.getDecoder().decode(encrypted);
            byte[] iv = java.util.Arrays.copyOfRange(data, 0, 12);
            byte[] payload = java.util.Arrays.copyOfRange(data, 12, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt TOTP secret", exception);
        }
    }

    private static String generateCode(String secret, long counter) {
        try {
            byte[] key = decodeBase32(secret);
            byte[] message = new byte[8];
            for (int i = 7; i >= 0; i--) {
                message[i] = (byte) counter;
                counter >>>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(message);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not verify TOTP code", exception);
        }
    }

    private static String hashRecoveryCode(String code) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(code.replace("-", "").toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash recovery code", exception);
        }
    }

    private static String encodeBase32(byte[] bytes) {
        StringBuilder result = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                result.append(BASE32.charAt((buffer >>> bits) & 31));
            }
        }
        if (bits > 0) result.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return result.toString();
    }

    private static byte[] decodeBase32(String value) {
        String normalized = value.replace("=", "").toUpperCase(Locale.ROOT);
        byte[] result = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char character : normalized.toCharArray()) {
            int valueIndex = BASE32.indexOf(character);
            if (valueIndex < 0) throw new IllegalArgumentException("Invalid Base32 secret");
            buffer = (buffer << 5) | valueIndex;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                result[index++] = (byte) ((buffer >>> bits) & 0xff);
            }
        }
        return result;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
