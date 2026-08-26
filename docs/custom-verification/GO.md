# Go 1.22

Используются только пакеты стандартной библиотеки.

```go
package main

import (
    "crypto/hmac"
    "crypto/sha256"
    "encoding/hex"
    "encoding/json"
    "fmt"
    "io"
    "net/http"
    "os"
    "strconv"
    "time"
)

func verification(w http.ResponseWriter, r *http.Request) {
    if r.Method != http.MethodPost {
        http.Error(w, "method_not_allowed", http.StatusMethodNotAllowed)
        return
    }

    body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 32*1024))
    if err != nil {
        http.Error(w, "invalid_body", http.StatusBadRequest)
        return
    }

    timestamp := r.Header.Get("X-PnAuth-Timestamp")
    nonce := r.Header.Get("X-PnAuth-Nonce")
    received, err := hex.DecodeString(r.Header.Get("X-PnAuth-Signature"))
    sentAt, timeErr := strconv.ParseInt(timestamp, 10, 64)
    if err != nil || timeErr != nil || abs(time.Now().Unix()-sentAt) > 60 {
        http.Error(w, "unauthorized", http.StatusUnauthorized)
        return
    }

    digest := sha256.Sum256(body)
    canonical := fmt.Sprintf("POST\n%s\n%s\n%s\n%x", r.URL.Path, timestamp, nonce, digest)
    mac := hmac.New(sha256.New, []byte(os.Getenv("PNAUTH_CUSTOM_PROVIDER_SECRET")))
    mac.Write([]byte(canonical))

    if !hmac.Equal(mac.Sum(nil), received) {
        http.Error(w, "invalid_signature", http.StatusUnauthorized)
        return
    }

    // atomicInsertNonce должен делать INSERT UNIQUE или Redis SET NX EX.
    if !atomicInsertNonce(nonce, time.Now().Add(60*time.Second)) {
        http.Error(w, "replay", http.StatusConflict)
        return
    }

    var event map[string]any
    if json.Unmarshal(body, &event) != nil {
        http.Error(w, "invalid_json", http.StatusBadRequest)
        return
    }
    // saveTicket(event)
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(http.StatusAccepted)
    w.Write([]byte(`{"accepted":true}`))
}

func abs(value int64) int64 {
    if value < 0 { return -value }
    return value
}
```

Добавьте свою реализацию `atomicInsertNonce` и `saveTicket` поверх SQL или Redis. Перед показом решения проверяйте связь пользователя сайта с Minecraft UUID из `playerId`.

