# Java 21 + Spring Boot

Нужен обычный Spring Web starter. Важно принимать body как `byte[]`, а не сразу как DTO.

```java
package example.pnauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public final class PnAuthVerificationController {
    private final byte[] secret = System.getenv("PNAUTH_CUSTOM_PROVIDER_SECRET")
        .getBytes(StandardCharsets.UTF_8);
    private final NonceRepository nonces;
    private final TicketRepository tickets;

    public PnAuthVerificationController(NonceRepository nonces, TicketRepository tickets) {
        this.nonces = nonces;
        this.tickets = tickets;
    }

    @PostMapping(path = "/api/pnauth/verification", consumes = "application/json")
    public ResponseEntity<?> receive(
        @RequestHeader("X-PnAuth-Schema") String schema,
        @RequestHeader("X-PnAuth-Timestamp") String timestamp,
        @RequestHeader("X-PnAuth-Nonce") String nonce,
        @RequestHeader("X-PnAuth-Signature") String received,
        @RequestBody byte[] rawBody
    ) throws Exception {
        if (!schema.equals("pnauth.verification.v1"))
            return ResponseEntity.badRequest().body("unsupported_schema");

        long sentAt;
        try { sentAt = Long.parseLong(timestamp); }
        catch (NumberFormatException error) { return ResponseEntity.status(401).body("stale_request"); }

        if (Math.abs(Instant.now().getEpochSecond() - sentAt) > 60)
            return ResponseEntity.status(401).body("stale_request");

        String digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(rawBody)
        );
        String path = "/api/pnauth/verification";
        String canonical = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + digest;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] supplied;
        try { supplied = HexFormat.of().parseHex(received); }
        catch (IllegalArgumentException error) { return ResponseEntity.status(401).body("invalid_signature"); }

        if (!MessageDigest.isEqual(expected, supplied))
            return ResponseEntity.status(401).body("invalid_signature");

        // insertOnce должен быть атомарным: INSERT с UNIQUE/PRIMARY KEY.
        if (!nonces.insertOnce(nonce, Instant.now().plusSeconds(60)))
            return ResponseEntity.status(409).body("replay");

        tickets.saveRawJson(rawBody);
        return ResponseEntity.accepted().body("{\"accepted\":true}");
    }
}
```

`NonceRepository.insertOnce` нельзя реализовывать через «сначала SELECT, затем INSERT»: два параллельных запроса создадут race condition. Используйте PRIMARY KEY/UNIQUE INSERT.

