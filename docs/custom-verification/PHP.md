# PHP 8.2 + PDO

## Установка

Дополнительные пакеты не нужны. Требуются PHP 8.2, PDO и драйвер вашей SQL-базы.

Environment:

```dotenv
PNAUTH_CUSTOM_PROVIDER_SECRET=replace-with-at-least-32-random-characters
DATABASE_DSN=mysql:host=127.0.0.1;dbname=website;charset=utf8mb4
DATABASE_USER=website
DATABASE_PASSWORD=secret
```

## Endpoint

Маршрут nginx/Apache должен направлять `POST /api/pnauth/verification` в этот файл.

```php
<?php
declare(strict_types=1);

function response(int $code, array $body): never {
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') response(405, ['error' => 'method_not_allowed']);

$rawBody = file_get_contents('php://input');
$secret = getenv('PNAUTH_CUSTOM_PROVIDER_SECRET') ?: '';
$timestamp = $_SERVER['HTTP_X_PNAUTH_TIMESTAMP'] ?? '';
$nonce = $_SERVER['HTTP_X_PNAUTH_NONCE'] ?? '';
$received = strtolower($_SERVER['HTTP_X_PNAUTH_SIGNATURE'] ?? '');
$schema = $_SERVER['HTTP_X_PNAUTH_SCHEMA'] ?? '';

if (strlen($secret) < 32) response(500, ['error' => 'secret_not_configured']);
if ($schema !== 'pnauth.verification.v1') response(400, ['error' => 'unsupported_schema']);
if (!ctype_digit($timestamp) || abs(time() - (int)$timestamp) > 60) {
    response(401, ['error' => 'stale_request']);
}
if (!preg_match('/^[A-Za-z0-9_-]{20,128}$/', $nonce)) {
    response(400, ['error' => 'invalid_nonce']);
}

$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$digest = hash('sha256', $rawBody);
$canonical = "POST\n{$path}\n{$timestamp}\n{$nonce}\n{$digest}";
$expected = hash_hmac('sha256', $canonical, $secret);
if (!hash_equals($expected, $received)) response(401, ['error' => 'invalid_signature']);

$event = json_decode($rawBody, true, 32, JSON_THROW_ON_ERROR);
if (($event['schema'] ?? '') !== 'pnauth.verification.v1' ||
    ($event['event'] ?? '') !== 'verification.requested') {
    response(400, ['error' => 'invalid_event']);
}
$expiresAt = strtotime((string)($event['expiresAt'] ?? ''));
if (!$expiresAt || $expiresAt <= time()) response(410, ['error' => 'expired']);

$pdo = new PDO(
    getenv('DATABASE_DSN'),
    getenv('DATABASE_USER'),
    getenv('DATABASE_PASSWORD'),
    [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
);

try {
    $pdo->beginTransaction();

    // PRIMARY KEY делает replay-защиту атомарной даже при нескольких PHP workers.
    $statement = $pdo->prepare(
        'INSERT INTO pnauth_webhook_nonces (nonce, expires_at) VALUES (?, ?)'
    );
    $statement->execute([$nonce, time() + 60]);

    $statement = $pdo->prepare(
        'INSERT INTO pnauth_verifications
         (ticket_id, player_id, username, operation, message, approve_url, deny_url, expires_at, status)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)'
    );
    $statement->execute([
        $event['ticketId'], $event['playerId'], $event['username'],
        $event['operation'], $event['message'], $event['approveUrl'],
        $event['denyUrl'], $expiresAt, 'PENDING'
    ]);

    $pdo->commit();
} catch (PDOException $error) {
    if ($pdo->inTransaction()) $pdo->rollBack();
    if ((string)$error->getCode() === '23000') response(409, ['error' => 'replay_or_duplicate']);
    throw $error;
}

response(202, ['accepted' => true]);
```

## Кнопка решения

После обычной авторизации на сайте получите Minecraft UUID пользователя из вашей таблицы привязок:

```php
<?php
session_start();

$ticket = findTicket($_GET['ticket'] ?? '');
$linkedUuid = $_SESSION['minecraft_uuid'] ?? null;

if (!$ticket || $ticket['expires_at'] <= time()) {
    http_response_code(410); exit('Запрос истёк');
}
if (!is_string($linkedUuid) || !hash_equals($ticket['player_id'], $linkedUuid)) {
    http_response_code(403); exit('Это запрос другого игрока');
}

$target = ($_POST['decision'] ?? '') === 'approve'
    ? $ticket['approve_url']
    : $ticket['deny_url'];

header('Location: ' . $target, true, 303);
```

SQL-схема приведена в [полной спецификации](../CUSTOM_VERIFICATION_RU.md).

