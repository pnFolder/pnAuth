# Python 3.11 + FastAPI

## Установка

```bash
pip install fastapi uvicorn
```

## Приложение

```python
import hashlib
import hmac
import json
import os
import time
from fastapi import FastAPI, Header, HTTPException, Request, Response

app = FastAPI()
secret = os.environ["PNAUTH_CUSTOM_PROVIDER_SECRET"].encode()
used_nonces: dict[str, float] = {}

@app.post("/api/pnauth/verification")
async def verification(
    request: Request,
    x_pnauth_schema: str = Header(default=""),
    x_pnauth_timestamp: str = Header(default=""),
    x_pnauth_nonce: str = Header(default=""),
    x_pnauth_signature: str = Header(default=""),
):
    # Берём bytes до JSON parsing.
    raw_body = await request.body()

    if x_pnauth_schema != "pnauth.verification.v1":
        raise HTTPException(400, "unsupported_schema")
    if not x_pnauth_timestamp.isdigit() or abs(time.time() - int(x_pnauth_timestamp)) > 60:
        raise HTTPException(401, "stale_request")
    if x_pnauth_nonce in used_nonces:
        raise HTTPException(409, "replay")

    digest = hashlib.sha256(raw_body).hexdigest()
    canonical = (
        f"POST\n{request.url.path}\n{x_pnauth_timestamp}\n"
        f"{x_pnauth_nonce}\n{digest}"
    ).encode()
    expected = hmac.new(secret, canonical, hashlib.sha256).hexdigest()

    if not hmac.compare_digest(expected, x_pnauth_signature.lower()):
        raise HTTPException(401, "invalid_signature")

    event = json.loads(raw_body)
    if event.get("schema") != "pnauth.verification.v1" or event.get("event") != "verification.requested":
        raise HTTPException(400, "invalid_event")

    # Для нескольких workers замените dict на SQL INSERT UNIQUE или Redis SET NX EX.
    used_nonces[x_pnauth_nonce] = time.time() + 60

    # await repository.save(event)
    # await notifier.send_to_linked_player(event["playerId"], event)
    return Response('{"accepted":true}', status_code=202, media_type="application/json")
```

Запуск:

```bash
uvicorn app:app --host 127.0.0.1 --port 8080
```

Для production nonce и tickets храните в общей базе, а не в словаре процесса. Перед показом кнопок сравнивайте UUID пользователя сайта с `event["playerId"]`.

