# Node.js 20 + Express

## Установка

```bash
npm install express
```

## Приложение

```js
import crypto from 'node:crypto';
import express from 'express';

const app = express();
const secret = process.env.PNAUTH_CUSTOM_PROVIDER_SECRET;
const usedNonces = new Map();

// express.raw обязателен: подпись считается от исходных bytes.
app.post(
  '/api/pnauth/verification',
  express.raw({ type: 'application/json', limit: '32kb' }),
  async (req, res) => {
    const timestamp = req.get('X-PnAuth-Timestamp') ?? '';
    const nonce = req.get('X-PnAuth-Nonce') ?? '';
    const received = (req.get('X-PnAuth-Signature') ?? '').toLowerCase();

    if (req.get('X-PnAuth-Schema') !== 'pnauth.verification.v1')
      return res.status(400).json({ error: 'unsupported_schema' });
    if (!/^\d+$/.test(timestamp) || Math.abs(Date.now() / 1000 - Number(timestamp)) > 60)
      return res.status(401).json({ error: 'stale_request' });
    if (usedNonces.has(nonce))
      return res.status(409).json({ error: 'replay' });

    const digest = crypto.createHash('sha256').update(req.body).digest('hex');
    const canonical = `POST\n${req.path}\n${timestamp}\n${nonce}\n${digest}`;
    const expected = crypto.createHmac('sha256', secret).update(canonical).digest('hex');
    const left = Buffer.from(expected, 'hex');
    const right = Buffer.from(received, 'hex');

    if (left.length !== right.length || !crypto.timingSafeEqual(left, right))
      return res.status(401).json({ error: 'invalid_signature' });

    const event = JSON.parse(req.body.toString('utf8'));
    if (event.schema !== 'pnauth.verification.v1' || event.event !== 'verification.requested')
      return res.status(400).json({ error: 'invalid_event' });

    // В production: SQL UNIQUE INSERT или Redis SET nonce value NX EX 60.
    usedNonces.set(nonce, Date.now() + 60_000);
    // await tickets.save(event);
    // await notifications.send(event.playerId, event);

    return res.status(202).json({ accepted: true });
  }
);

app.listen(8080, '127.0.0.1');
```

Перед выдачей ссылок сравните UUID текущего пользователя сайта с `event.playerId`. Для нескольких Node workers используйте общую SQL/Redis replay-защиту.

