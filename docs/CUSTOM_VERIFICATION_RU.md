# Интеграция custom verification

Этот протокол позволяет pnAuth запросить подтверждение входа или опасной операции у вашего сайта, панели либо бота. Внешний сервис не получает пароль, password hash, TOTP secret или recovery-коды.

## 1. Схема обмена

1. Игрок проходит основной этап проверки в Minecraft.
2. pnAuth создаёт одноразовый ticket с TTL.
3. pnAuth отправляет подписанный POST на ваш HTTPS endpoint.
4. Ваш сервис проверяет HMAC, timestamp и уникальность nonce, затем сохраняет запрос.
5. Авторизованный пользователь сайта видит запрос и выбирает «Разрешить» или «Отклонить».
6. Браузер открывает переданный approveUrl либо denyUrl.
7. pnAuth показывает промежуточную страницу. Финальное решение выполняется только её POST-формой — preview-бот не сможет подтвердить действие обычным GET.
8. Ticket закрывается один раз; просроченный, повторный или относящийся к старой игровой сессии ticket отклоняется.

## 2. Конфигурация pnAuth

~~~yaml
external-verification:
  enabled: true
  operations:
    - LOGIN
    - CHANGE_PASSWORD
    - UNREGISTER
  lifetime-seconds: 300

  callback:
    host: 127.0.0.1
    port: 8765
    public-url: https://minecraft.example.com

  custom:
    enabled: true
    url: https://account.example.com/api/pnauth/verification
    secret: "${ENV:PNAUTH_CUSTOM_PROVIDER_SECRET}"
~~~

callback.public-url должен через HTTPS reverse proxy вести на локальный callback.host:callback.port. Переменная PNAUTH_CUSTOM_PROVIDER_SECRET должна быть одинаковой у pnAuth и сайта и содержать минимум 32 случайных символа.

## 3. Формат запроса

~~~http
POST /api/pnauth/verification HTTP/1.1
Content-Type: application/json
X-PnAuth-Schema: pnauth.verification.v1
X-PnAuth-Timestamp: 1787342400
X-PnAuth-Nonce: q7Y...32_chars
X-PnAuth-Signature: 9ae...64_hex_chars
~~~

~~~json
{
  "schema": "pnauth.verification.v1",
  "event": "verification.requested",
  "ticketId": "ticket-id",
  "playerId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "Steve",
  "operation": "LOGIN",
  "message": "Подтвердите действие во внешнем мессенджере",
  "expiresAt": "2026-08-21T20:30:00Z",
  "approveUrl": "https://minecraft.example.com/pnauth/verification/...",
  "denyUrl": "https://minecraft.example.com/pnauth/verification/..."
}
~~~

Не кодируйте JSON заново перед проверкой подписи: SHA-256 считается от исходных байтов HTTP body.

## 4. Проверка подписи

Каноническая строка:

~~~text
POST\n<URL path>\n<timestamp>\n<nonce>\n<lowercase SHA-256 hex исходного body>
~~~

Для URL https://account.example.com/api/pnauth/verification path равен /api/pnauth/verification. Query string в подпись версии v1 не входит.

~~~text
signature = lowercase_hex(HMAC-SHA256(secret, canonical_string))
~~~

Сравнивайте подписи constant-time функцией. Рекомендуемый допуск часов — 30–60 секунд. Каждый nonce сохраняйте до истечения окна и принимайте только один раз.

## 5. Полный PHP 8.2 endpoint

Пример использует PDO и таблицу с уникальным nonce. Для production используйте PostgreSQL/MySQL и HTTPS.

~~~php
<?php
declare(strict_types=1);

const MAX_CLOCK_SKEW = 60;

function fail(int $status, string $message): never {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['error' => $message], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') fail(405, 'method_not_allowed');

$secret = getenv('PNAUTH_CUSTOM_PROVIDER_SECRET') ?: '';
if (strlen($secret) < 32) fail(500, 'server_secret_is_not_configured');

$body = file_get_contents('php://input');
$timestamp = $_SERVER['HTTP_X_PNAUTH_TIMESTAMP'] ?? '';
$nonce = $_SERVER['HTTP_X_PNAUTH_NONCE'] ?? '';
$received = strtolower($_SERVER['HTTP_X_PNAUTH_SIGNATURE'] ?? '');
$schema = $_SERVER['HTTP_X_PNAUTH_SCHEMA'] ?? '';

if ($schema !== 'pnauth.verification.v1') fail(400, 'unsupported_schema');
if (!ctype_digit($timestamp) || abs(time() - (int)$timestamp) > MAX_CLOCK_SKEW) fail(401, 'stale_request');
if (!preg_match('/^[A-Za-z0-9_-]{20,128}$/', $nonce)) fail(400, 'invalid_nonce');

$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$digest = hash('sha256', $body);
$canonical = "POST\n{$path}\n{$timestamp}\n{$nonce}\n{$digest}";
$expected = hash_hmac('sha256', $canonical, $secret);
if (!hash_equals($expected, $received)) fail(401, 'invalid_signature');

$event = json_decode($body, true, 32, JSON_THROW_ON_ERROR);
if (($event['schema'] ?? '') !== 'pnauth.verification.v1' ||
    ($event['event'] ?? '') !== 'verification.requested') fail(400, 'invalid_event');
if (strtotime((string)($event['expiresAt'] ?? '')) <= time()) fail(410, 'expired');

$pdo = new PDO(getenv('DATABASE_DSN'), getenv('DATABASE_USER'), getenv('DATABASE_PASSWORD'), [
    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
]);
$pdo->beginTransaction();
try {
    $nonceInsert = $pdo->prepare(
        'INSERT INTO pnauth_webhook_nonces (nonce, expires_at) VALUES (:nonce, :expires_at)'
    );
    $nonceInsert->execute(['nonce' => $nonce, 'expires_at' => time() + MAX_CLOCK_SKEW]);

    $ticketInsert = $pdo->prepare(
        'INSERT INTO pnauth_verifications
         (ticket_id, player_id, username, operation, message, approve_url, deny_url, expires_at, status)
         VALUES (:ticket_id, :player_id, :username, :operation, :message, :approve_url, :deny_url, :expires_at, :status)'
    );
    $ticketInsert->execute([
        'ticket_id' => $event['ticketId'],
        'player_id' => $event['playerId'],
        'username' => $event['username'],
        'operation' => $event['operation'],
        'message' => $event['message'],
        'approve_url' => $event['approveUrl'],
        'deny_url' => $event['denyUrl'],
        'expires_at' => strtotime($event['expiresAt']),
        'status' => 'PENDING',
    ]);
    $pdo->commit();
} catch (PDOException $e) {
    $pdo->rollBack();
    if ((string)$e->getCode() === '23000') fail(409, 'replayed_or_duplicate_request');
    throw $e;
}

http_response_code(202);
header('Content-Type: application/json; charset=utf-8');
echo '{"accepted":true}';
~~~

Минимальные таблицы:

~~~sql
CREATE TABLE pnauth_webhook_nonces (
    nonce VARCHAR(128) PRIMARY KEY,
    expires_at BIGINT NOT NULL
);

CREATE TABLE pnauth_verifications (
    ticket_id VARCHAR(128) PRIMARY KEY,
    player_id VARCHAR(36) NOT NULL,
    username VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    message TEXT NOT NULL,
    approve_url TEXT NOT NULL,
    deny_url TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL
);
~~~

Страница сайта обязана сначала авторизовать пользователя и проверить, что его связанный Minecraft UUID равен player_id. Только после этого можно перенаправить браузер на сохранённый approve_url или deny_url:

~~~php
<?php
session_start();
// Реализуйте loadTicketFromDatabase() и собственную таблицу привязок сайта к Minecraft UUID.
$linkedMinecraftUuid = $_SESSION['minecraft_uuid'] ?? null;
$ticket = loadTicketFromDatabase($_GET['ticket'] ?? '');

if (!$ticket || $ticket['status'] !== 'PENDING' || $ticket['expires_at'] <= time()) {
    http_response_code(410); exit('Запрос истёк');
}
if (!is_string($linkedMinecraftUuid) ||
    !hash_equals($ticket['player_id'], $linkedMinecraftUuid)) {
    http_response_code(403); exit('Этот запрос принадлежит другому игроку');
}
$field = ($_POST['decision'] ?? '') === 'approve' ? 'approve_url' : 'deny_url';
header('Location: ' . $ticket[$field], true, 303);
~~~

## 6. Пример Node.js/Express

Критично использовать express.raw, иначе JSON будет изменён до проверки SHA-256.

~~~js
import crypto from 'node:crypto';
import express from 'express';

const app = express();
const usedNonces = new Map();

app.post('/api/pnauth/verification', express.raw({ type: 'application/json', limit: '32kb' }), (req, res) => {
  const timestamp = req.get('X-PnAuth-Timestamp') ?? '';
  const nonce = req.get('X-PnAuth-Nonce') ?? '';
  const received = req.get('X-PnAuth-Signature') ?? '';
  if (!/^\d+$/.test(timestamp) || Math.abs(Date.now() / 1000 - Number(timestamp)) > 60)
    return res.status(401).json({ error: 'stale_request' });
  if (usedNonces.has(nonce)) return res.status(409).json({ error: 'replay' });

  const digest = crypto.createHash('sha256').update(req.body).digest('hex');
  const canonical = `POST\n${req.path}\n${timestamp}\n${nonce}\n${digest}`;
  const expected = crypto.createHmac('sha256', process.env.PNAUTH_CUSTOM_PROVIDER_SECRET)
    .update(canonical).digest('hex');
  const a = Buffer.from(expected, 'hex');
  const b = Buffer.from(received, 'hex');
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b))
    return res.status(401).json({ error: 'invalid_signature' });

  usedNonces.set(nonce, Date.now() + 60_000);
  const event = JSON.parse(req.body.toString('utf8'));
  // Сохранить ticket и показать его только владельцу связанного event.playerId.
  res.status(202).json({ accepted: true });
});
~~~

В нескольких экземплярах сайта nonce и tickets должны храниться в общей SQL-базе или Redis с atomic SET NX EX, а не в памяти процесса.

## 7. Reverse proxy callback pnAuth

Пример nginx:

~~~nginx
location /pnauth/verification/ {
    proxy_pass http://127.0.0.1:8765;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto https;
    limit_except GET POST { deny all; }
}
~~~

Не публикуйте порт 8765 напрямую. TLS завершается на nginx/Caddy/Traefik, а firewall разрешает локальный порт только с localhost.

## 8. Production checklist

- HTTPS с валидным сертификатом.
- Секрет находится в environment/secret manager, а не в Git.
- Проверяются schema в header и JSON.
- Подпись проверяется до JSON parsing и побочных действий.
- Timestamp имеет небольшое допустимое окно.
- Nonce принимается атомарно и только один раз.
- Ticket показывается только владельцу связанного Minecraft UUID.
- approveUrl/denyUrl не пишутся в публичные логи и аналитику.
- Истёкшие tickets и nonce регулярно удаляются.
- Endpoint быстро отвечает, уведомления отправляются через очередь.
- Неизвестное operation отклоняется безопасно.

## 9. Ответы endpoint

- 202 — событие проверено и принято.
- 400 — повреждённая schema/body/nonce.
- 401 — подпись неверна или timestamp устарел.
- 409 — replay либо duplicate ticket.
- 410 — ticket уже истёк.
- 500 — ошибка конфигурации сервиса.

pnAuth считает любой 2xx успешной доставкой. Другой статус записывается как предупреждение. Автоматической очереди повторной доставки в протоколе v1 пока нет, поэтому production endpoint должен быть высокодоступным.
