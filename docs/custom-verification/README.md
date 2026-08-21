# Custom verification: быстрый старт

Эта инструкция предназначена для разработчика сайта, панели или бота. Для первого запуска достаточно пройти разделы 1–5. Детали безопасности и production-развёртывание находятся в [полной спецификации](../CUSTOM_VERIFICATION_RU.md).

## Что именно строим

pnAuth не просит ваш сайт проверять пароль Minecraft. Пароль уже проверяет pnAuth или pnAuth Hub. Сайт получает только просьбу:

> Игрок Steve пытается выполнить LOGIN. Покажи запрос владельцу этого аккаунта и дай ему разрешить или отклонить действие.

Обмен состоит из двух направлений:

```text
Minecraft                         Ваш сайт
─────────                         ─────────
Игрок ввёл пароль
pnAuth создал ticket
        │
        ├──── подписанный POST ──► endpoint принимает и сохраняет ticket
        │                          пользователь сайта видит две кнопки
        │                                      │
        ◄──── callback URL открывается браузером
pnAuth показывает финальную
страницу подтверждения
        │
        ◄──── пользователь нажимает POST-кнопку
вход разрешён или отклонён
```

## 1. Выберите готовый пример

| Стек | Файл | Что используется |
|---|---|---|
| PHP 8.2+ | [PHP.md](PHP.md) | обычный PHP, PDO |
| Python 3.11+ | [PYTHON.md](PYTHON.md) | FastAPI |
| Node.js 20+ | [NODEJS.md](NODEJS.md) | Express |
| Java 21+ | [JAVA_SPRING.md](JAVA_SPRING.md) | Spring Boot |
| Go 1.22+ | [GO.md](GO.md) | стандартная библиотека |

У всех примеров один алгоритм. Язык меняет только синтаксис.

## 2. Создайте секрет

Сгенерируйте случайный секрет минимум из 32 байт. Пример:

```text
PNAUTH_CUSTOM_PROVIDER_SECRET=<случайное значение>
```

Один и тот же секрет задаётся:

- в environment процесса pnAuth;
- в environment вашего сайта.

Не помещайте секрет в Git, клиентский JavaScript, HTML или публичный YAML.

## 3. Настройте pnAuth

```yaml
external-verification:
  enabled: true
  operations:
    - LOGIN
  lifetime-seconds: 300

  callback:
    host: 127.0.0.1
    port: 8765
    public-url: https://minecraft.example.com

  custom:
    enabled: true
    url: https://account.example.com/api/pnauth/verification
    secret: "${ENV:PNAUTH_CUSTOM_PROVIDER_SECRET}"
```

Значения означают:

| Параметр | Назначение |
|---|---|
| `operations` | какие действия требуют второго подтверждения |
| `lifetime-seconds` | сколько живёт один запрос |
| `callback.public-url` | публичный HTTPS-адрес встроенной страницы pnAuth |
| `custom.url` | endpoint вашего сайта, куда pnAuth отправляет JSON |
| `custom.secret` | общий HMAC-секрет |

## 4. Реализуйте четыре операции на сайте

Независимо от языка сервис должен уметь только следующее:

1. Прочитать **исходные байты** HTTP body.
2. Проверить HMAC-подпись, timestamp и одноразовый nonce.
3. Сохранить ticket и связать его с `playerId`.
4. После авторизации пользователя сайта показать ему кнопки, ведущие на `approveUrl` и `denyUrl`.

Критически важно: запрос разрешается показывать только пользователю, чей привязанный Minecraft UUID совпадает с `playerId`.

## 5. Что приходит от pnAuth

Headers:

```text
X-PnAuth-Schema: pnauth.verification.v1
X-PnAuth-Timestamp: Unix time в секундах
X-PnAuth-Nonce: случайное одноразовое значение
X-PnAuth-Signature: lowercase HMAC-SHA256 hex
```

Body:

```json
{
  "schema": "pnauth.verification.v1",
  "event": "verification.requested",
  "ticketId": "уникальный-ticket",
  "playerId": "Minecraft UUID",
  "username": "Steve",
  "operation": "LOGIN",
  "message": "Подтвердите действие",
  "expiresAt": "2026-08-21T20:30:00Z",
  "approveUrl": "https://minecraft.example.com/...",
  "denyUrl": "https://minecraft.example.com/..."
}
```

Пароль, password hash, TOTP secret и recovery-коды не отправляются.

## 6. Единый алгоритм подписи

```text
bodyDigest = lowercaseHex(SHA256(rawBody))

canonical =
  "POST" + "\n" +
  requestPath + "\n" +
  timestamp + "\n" +
  nonce + "\n" +
  bodyDigest

expectedSignature = lowercaseHex(HMAC_SHA256(secret, canonical))
```

`requestPath` — только path URL, например `/api/pnauth/verification`, без домена и query string.

Затем:

- timestamp должен отличаться от текущего времени не больше чем на 60 секунд;
- nonce должен быть новым;
- подпись сравнивается constant-time функцией;
- nonce атомарно сохраняется до конца временного окна;
- только после этого JSON разбирается и сохраняется.

## 7. Как выглядит интерфейс сайта

```text
Новый запрос pnAuth

Игрок: Steve
Операция: Вход
Истекает через: 04:32

[ Разрешить ]  [ Отклонить ]
```

Кнопка не должна отправлять решение из backend без проверки пользователя. Рекомендуемая последовательность:

1. Пользователь вошёл на сайт.
2. Сайт знает его связанный Minecraft UUID.
3. UUID сравнивается с `playerId` ticket.
4. Браузер получает redirect 303 на `approveUrl` или `denyUrl`.
5. Встроенная страница pnAuth просит нажать финальную POST-кнопку.

## 8. Проверка интеграции

Успешный сценарий:

1. Запустить pnAuth с включённым custom provider.
2. Войти тестовым игроком.
3. Убедиться, что endpoint вернул HTTP 202.
4. Проверить, что ticket появился только у правильного пользователя сайта.
5. Нажать «Разрешить».
6. Подтвердить действие на странице pnAuth.
7. Убедиться, что игрок авторизован.
8. Повторно открыть ту же ссылку — должен вернуться HTTP 410.

Негативные проверки:

- изменить один байт body — HTTP 401;
- повторить nonce — HTTP 409;
- использовать старый timestamp — HTTP 401;
- открыть ticket из другого аккаунта сайта — HTTP 403;
- использовать истёкший ticket — HTTP 410.

## 9. Что возвращать pnAuth

| HTTP | Значение |
|---|---|
| 202 | запрос проверен и сохранён |
| 400 | неправильная schema или JSON |
| 401 | неправильная подпись или старый timestamp |
| 409 | nonce/ticket уже использован |
| 410 | ticket уже истёк |
| 500 | внутренняя ошибка сервиса |

pnAuth считает доставкой любой ответ 2xx.

