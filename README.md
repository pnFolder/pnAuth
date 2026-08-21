# pnAuth

pnAuth — плагин авторизации для прокси BungeeCord и Velocity. Один JAR поддерживает обе платформы и включает регистрацию, вход, опциональные сессии по IP, 2FA/TOTP, recovery-коды, CAPTCHA, миграции, лимиты по IP и встроенный auth-limbo.

Для сетей дополнительно собирается `pnAuth-hub-<version>.jar`: центральный auth-сервис, который хранит аккаунты в своей SQL-базе, проверяет пароли/TOTP и синхронизирует узлы. Redis никогда не используется для паролей, password hash, TOTP-секретов или recovery-кодов.

## Возможности

- Русский и английский интерфейс: `locale: ru` или `locale: en`.
- Пароли PBKDF2, BCrypt или Argon2id; PBKDF2-HMAC-SHA256 с 600 000 итераций используется по умолчанию.
- Двухфакторная аутентификация с зашифрованными TOTP-секретами и одноразовыми recovery-кодами.
- Ограничение неудачных попыток, временная блокировка и лимиты аккаунтов по IP.
- Command- и native dialog-интерфейсы, CAPTCHA, title/actionbar-напоминания.
- Встроенное подтверждение входа и опасных действий через Discord, Telegram или VK.
- SQLite, H2, MySQL, MariaDB, PostgreSQL или произвольный JDBC URL.
- Импорт из AuthMe, nLogin, LimboAuth, McAuth и TiaAuth.

## Установка

1. Соберите или скачайте `pnAuth-<version>.jar`.
2. Поместите JAR в каталог `plugins` BungeeCord или Velocity.
3. Запустите прокси один раз. Будет создан `plugins/pnAuth/config.yml` и каталог `messages`.
4. Настройте `config.yml`, затем перезапустите прокси.

Требуется Java 17 или новее. На сети из нескольких прокси используйте общую MySQL/MariaDB/PostgreSQL базу и одинаковый файл `totp.key` на всех узлах. Не публикуйте этот ключ: без него нельзя расшифровать 2FA-секреты.

## Основные команды

| Команда | Назначение |
| --- | --- |
| `/register <пароль> <повтор>` | Регистрация аккаунта |
| `/login <пароль>` | Вход |
| `/logout` | Выход из текущей сессии |
| `/changepassword <старый> <новый>` | Смена пароля |
| `/unregister <пароль>` | Удаление аккаунта |
| `/totp enable <пароль>` | Начать настройку 2FA (пароль обязателен) |
| `/totp verify <код>` | Подтвердить настройку или войти с 2FA |
| `/totp disable <пароль> <код>` | Отключить 2FA |
| `/status` | Показать статус авторизации |
| `/auth ui <auto\|on\|off>` | Выбрать dialog UI или команды |

### Администрирование

Админ-команды требуют соответствующее право `pnauth.admin.commands.<имя>`.

| Команда | Право |
| --- | --- |
| `/auth unregister <игрок>` | `pnauth.admin.commands.unregister` |
| `/auth changepassword <игрок> <пароль>` | `pnauth.admin.commands.changepassword` |
| `/auth forcelogin <игрок>` | `pnauth.admin.commands.forcelogin` |
| `/auth forceregister <игрок> <пароль>` | `pnauth.admin.commands.forceregister` |
| `/auth forcepremium <игрок>` | `pnauth.admin.commands.forcepremium` |
| `/auth broadcast <сообщение>` | `pnauth.admin.commands.broadcast` |
| `/auth migrate <источник> <JDBC URL> [user] [password]` | `pnauth.admin.commands.migrate` |

Premium-режим меняет режим аутентификации прокси, поэтому он доступен только администратору через `/auth forcepremium`; обычной команды самообслуживания нет.

## Конфигурация

Конфигурация описана в `PnAuthYamlConfig` и генерируется Elytrium Serializer. На первом запуске появляется полный `config.yml` с комментариями. При миграции схемы перед перезаписью создаётся `config.yml.bak`; актуальный файл с полной схемой не форматируется заново при обычном запуске.

```yaml
config-version: 6
locale: ru

messages:
  # LEGACY, MINI_MESSAGE, JSON или PLAIN
  format: LEGACY

database:
  # SQLITE, H2, MYSQL, MARIADB, POSTGRESQL или JDBC
  type: SQLITE
  file: auth.db
  mysql:
    host: 127.0.0.1
    port: 3306
    database: minecraft_auth
    username: ""
    password: ""
    use-ssl: true
    server-timezone: UTC

servers:
  auth-server: auth
  backend-server: hub
  # Включите на публичной сети, чтобы игрок не попал на backend до входа.
  require-auth-before-server: true

security:
  password:
    min-length: 8
    max-length: 64
    repeat-on-register: true
  login:
    max-attempts: 5
    lockout-seconds: 60
    ban-on-failed-login: true
    ban-seconds: 60
  hashing:
    algorithm: PBKDF2
    pbkdf2-iterations: 600000

features:
  session:
    # Выключено по умолчанию: один IP не является надёжным вторым фактором на NAT/VPN.
    restore-on-same-ip: false
    lifetime-minutes: 60
    timeout-seconds: 60
    reminder-seconds: 10
  totp:
    enabled: true
    max-attempts: 3
    lockout-seconds: 60
    setup-lifetime-seconds: 300
    issuer: "Minecraft Server"
    recovery-codes: 16
  captcha:
    enabled: false
    lifetime-seconds: 30
    max-attempts: 3

external-verification:
  # По умолчанию выключено: сначала настройте HTTPS callback и транспорт.
  enabled: false
  operations:
    - LOGIN
  lifetime-seconds: 300
  callback:
    host: 127.0.0.1
    port: 8765
    public-url: https://auth.example.com
  discord:
    enabled: false
    webhook-url: ""
  telegram:
    enabled: false
    bot-token: ""
    chat-id: ""
  vk:
    enabled: false
    access-token: ""
    peer-id: ""
    api-version: "5.199"
  custom:
    enabled: false
    url: https://auth.example.com/integrations/pnauth
    secret: "${ENV:PNAUTH_CUSTOM_PROVIDER_SECRET}"

cluster:
  # STANDALONE, SHARED_DATABASE, REDIS или HUB
  mode: STANDALONE
  node-id: proxy-1
  redis:
    uri: "${ENV:PNAUTH_REDIS_URI}"
    stream: pnauth:events
  hub:
    url: https://auth.example.com
    client-id: proxy-1
    client-secret: "${ENV:PNAUTH_HUB_CLIENT_SECRET}"
    connect-timeout-millis: 5000
```

`messages/messages_ru.yml` и `messages/messages_en.yml` создаются автоматически. Меняйте значения и оформление свободно: существующий файл никогда не перезаписывается. При обновлении отсутствующие новые ключи временно берутся из встроенного перевода, поэтому сервер остаётся рабочим без потери ваших комментариев.

## База данных

Для одиночного прокси достаточно SQLite. Для сети используйте одну общую базу:

```yaml
database:
  type: MYSQL
  mysql:
    host: 127.0.0.1
    port: 3306
    database: minecraft_auth
    username: minecraft
    password: change-me
    use-ssl: true
    server-timezone: UTC
```

Не храните `config.yml`, базы данных и `totp.key` в публичном репозитории. Они уже исключены через `.gitignore`.

Для MySQL, MariaDB и PostgreSQL TLS включён по умолчанию и проверяет сертификат сервера. Отключайте `use-ssl` только для локальной доверенной базы; для PostgreSQL установите корректный корневой сертификат в настройках драйвера/системы.

## Внешнее подтверждение

Модуль находится внутри pnAuth и использует общий verification-ticket ядра. Отдельный JAR или платформенная копия логики не нужны. Для выбранных операций плагин отправляет в Discord, Telegram и/или VK две кнопки: разрешить и отклонить. Ссылка одноразовая и подписана HMAC. Обычное открытие выполняет только безопасный просмотр; окончательное решение отправляется отдельным POST-нажатием, поэтому предпросмотр ссылок мессенджером не может случайно подтвердить вход.

Перед включением:

1. Направьте публичный HTTPS-адрес, например `https://auth.example.com`, через reverse proxy на `127.0.0.1:8765`.
2. Заполните настройки хотя бы одного транспорта.
3. Выберите действия в `operations` и установите `enabled: true`.
4. Не публикуйте `config.yml`: webhook и токены дают доступ к отправке сообщений от ваших ботов.

Если callback-сервер не может запуститься или конфигурация неполна, pnAuth завершает запуск с ошибкой вместо небезопасного обхода подтверждения.

## Синхронизация сети

- `STANDALONE` — один прокси/сервер и локальная база.
- `SHARED_DATABASE` — небольшая сеть использует общую MySQL/MariaDB/PostgreSQL базу. Служебная таблица передаёт только события сброса локальных сессий.
- `REDIS` — узлы используют общий SQL и Redis Streams как fan-out транспорт событий. Каждый узел читает поток со своей позиции; consumer group намеренно не используется, иначе событие получил бы только один прокси.
- `HUB` — пароли, password hash, TOTP и recovery-коды находятся только в SQL-базе Hub. Прокси отправляет пароль по HTTPS в момент операции; запрос подписывается HMAC-SHA256 с timestamp и nonce. Hub никогда не возвращает hash.

`REDIS` поддерживает `redis://` и `rediss://`; для удалённого Redis используйте только TLS. В событиях программно запрещены поля, содержащие `password`, `hash`, `secret`, `token`, `credential` или `recovery`.

Запуск Hub:

```bash
java -jar pnAuth-hub-1.0.0.jar /path/to/pnauth-hub
```

При первом запуске создаётся документированный русский `hub.yml`. Hub рекомендуется слушать на `127.0.0.1` за HTTPS reverse proxy. Секрет каждого node указывается через переменную окружения и должен содержать минимум 32 символа.

Пользовательский `custom` provider получает событие `pnauth.verification.v1`. Подписываются HTTP method, path, timestamp, nonce и SHA-256 тела. Это позволяет подключить собственный сайт, панель или бота без доступа к SQL и паролям.

Пошаговый старт и готовые примеры для PHP, Python, Node.js, Java/Spring и Go: [docs/custom-verification/README.md](docs/custom-verification/README.md). Полная спецификация, SQL-схема, nginx и production checklist: [docs/CUSTOM_VERIFICATION_RU.md](docs/CUSTOM_VERIFICATION_RU.md).

## Автономная авторизация на Paper/Folia

Paper/Folia — полноценный самостоятельный режим pnAuth без BungeeCord или Velocity. Плагин сам обрабатывает join, регистрацию, вход, TOTP и native dialog, а до авторизации блокирует настроенные действия игрока.

```yaml
paper:
  teleport:
    enabled: true
    world: auth
    x: 0.5
    y: 100.0
    z: 0.5
    yaw: 0.0
    pitch: 0.0

  success-teleport:
    # ORIGINAL, SPAWN, CUSTOM или NONE
    destination: ORIGINAL
    delay-millis: 500
    world: world
    x: 0.5
    y: 80.0
    z: 0.5
    yaw: 0.0
    pitch: 0.0

  restrictions:
    movement: true
    chat: true
    commands: true
    interaction: true
    breaking: true
    placing: true
    inventory: true
```

- `ORIGINAL` возвращает игрока в сохранённую точку входа.
- `SPAWN` отправляет на spawn указанного мира.
- `CUSTOM` использует заданные координаты.
- `NONE` не выполняет телепортацию после авторизации.

Планировщики телепортации совместимы с региональной моделью Folia. Для автономного Paper не нужен proxy-сервер; используется тот же `pnAuth-1.0.0.jar`.

## Limbo

Встроенный PicoLimbo отключён по умолчанию. При включении `limbo.enabled: true` укажите одинаковое значение для `servers.auth-server` и `limbo.server-name`; `servers.backend-server` должен быть другим сервером. Бинарный файл загружается только из заданного URL и проверяется SHA-256.

## API

### High-level dialogs

Extensions normally do not need to create Minecraft resource identifiers or inspect button action
strings. `DialogForm` generates a fresh transport identifier for every display and routes each
button directly to its callback:

```java
PnPlayer player = api.platform().player(playerId).orElseThrow();

DialogForm form = DialogForm.builder(Component.text("Discord verification"))
        .body(Component.text("Enter the code sent by the Discord bot."), 400)
        .text("code", Component.text("Verification code"), 32, 200)
        .button(Component.text("Confirm"), response -> {
            String code = response.string("code").orElseThrow();
            discordVerification.verify(player.uniqueId(), code);
        })
        .button(Component.text("Cancel"), response ->
                player.sendMessage(Component.text("Verification cancelled.")))
        .onClose(response -> cleanupPendingVerification(player.uniqueId()))
        .build();

DialogHandle handle = player.dialogs().show(player, form);
```

Only field names such as `code` are public application data. Dialog IDs, button action IDs,
connection ownership checks and PacketEvents NBT routing remain internal to pnAuth. Use the
lower-level `PlayerDialog` API only when an extension needs an exact vanilla dialog structure.


Платформонезависимый API и вся прикладная логика находятся в модуле `shared`. BungeeCord и Velocity являются тонкими адаптерами: они преобразуют события прокси во входные модели ядра и применяют готовые решения маршрутизации/доступа.

```java
AuthApi api = plugin.getApi();
api.isAuthenticated(playerUuid);
api.login(playerUuid, password).thenAccept(result -> { });
```

### Единая система событий

Стороннему плагину не нужно писать отдельные listener-ы pnAuth для BungeeCord и Velocity. Получите `AuthApi` у платформенного плагина и зарегистрируйте один и тот же обработчик:

```java
AuthSubscription subscription = api.events().subscribe(
    UserAuthenticatedEvent.class,
    event -> logger.info(event.username() + " authenticated via " + event.cause())
);

// При отключении вашего плагина:
subscription.close();
```

Доступны события подключения и выхода, регистрации и удаления, успешной авторизации и каждой попытки входа, смены пароля, premium-режима, TOTP, dialog preference, admission policy и внешней верификации. Подписка на `UserAuthEvent` получает все пользовательские auth-события, а подписка на `AuthEvent` — вообще все события pnAuth.

До защищённой операции публикуется отменяемый `PreAuthOperationEvent`:

```java
api.events().subscribe(PreAuthOperationEvent.class, event -> {
    if (event.context().operation() == AuthOperation.SERVER_ACCESS
            && maintenanceMode()) {
        event.cancel("maintenance");
    }
});
```

Отменяемые операции включают регистрацию, вход, выход, смену пароля, удаление аккаунта, TOTP, premium, admission, команды, чат и переходы между серверами. В атрибуты command-event попадает только имя команды — пароль и остальные аргументы никогда не публикуются.

### Extension Kernel

Дополнительный плагин может зависеть только от `ExtensionKernel`, не используя auth-сервисы:

```java
ExtensionKernel kernel = pnAuthPlugin.getKernel();
```

Kernel состоит из четырёх независимых частей:

- `events()` — общая шина событий любых плагинов. Собственное событие реализует `ExtensionEvent` и не обязано относиться к авторизации.
- `services()` — типобезопасный registry произвольных контрактов между плагинами.
- `display()` — прямой интерфейс управления actionbar, title и bossbar с реализацией текущей платформы.
- `extensions()` — policy hooks и внешние verification tickets, непосредственно используемые pnAuth.

Например, отдельный social-плагин может опубликовать собственный API:

```java
ServiceKey<DiscordLinkService> key = ServiceKey.of(
    "social", "discord-links", DiscordLinkService.class
);

ServiceRegistration registration = kernel.services().register(
    key, "my-discord-plugin", 100, discordLinkService
);

DiscordLinkService links = kernel.services().find(key).orElseThrow();
```

Service registry поддерживает несколько реализаций, приоритет `-5000..5000` и автоматический fallback после закрытия регистрации. Kernel не знает назначение сервиса: это может быть Discord, экономика, профиль, наказания или ещё не придуманная система.

### Приоритеты и решения

```java
kernel.events().subscribe(
    PreAuthOperationEvent.class,
    new ListenerOptions(
        "maintenance-plugin",
        EventPriority.HIGH,
        true,
        ListenerMode.MUTATING
    ),
    event -> event.setDecision(true, "maintenance")
);
```

Стандартные уровни: `LOWEST`, `LOW`, `NORMAL`, `HIGH`, `HIGHEST`, зарезервированный `SYSTEM` и read-only `MONITOR`. Пользовательское число ограничено диапазоном `-5000..5000`. Более высокий listener может вызвать `allow()` и заменить низкую отмену, если зарегистрирован с `receiveCancelled=true`. `MONITOR` получает окончательный результат, но менять его не может.

`effectiveDecision()` показывает итог, а `decisionHistory()` содержит полный журнал: `ownerId`, приоритет, решение, причину и время. Поэтому всегда известно, какой плагин запретил или повторно разрешил действие.

### Внешнее подтверждение: Discord, Telegram или собственный сервис

Для асинхронной проверки используйте policy hook. Он может разрешить операцию, запретить её или потребовать внешнее подтверждение:

```java
AuthExtensionRegistration discordPolicy = api.extensions().register(
    "discord",
    100, // чем больше число, тем раньше выполняется policy
    context -> CompletableFuture.completedFuture(
        context.operation() == AuthOperation.LOGIN
            ? AuthPolicyDecision.requireVerification(
                "discord", "Confirm login in Discord", Duration.ofMinutes(5))
            : AuthPolicyDecision.allow()
    )
);

AuthSubscription tickets = api.events().subscribe(
    VerificationRequiredEvent.class,
    event -> discordBot.sendConfirmation(
        event.ticket().username(),
        event.ticket().id()
    )
);

// Callback Discord-бота после подтверждения пользователем:
api.extensions().approve(ticketId);
```

Для login/TOTP policy вызывается в фазе `CREDENTIAL_VERIFIED`: неправильный пароль или код не создаёт запрос в Discord. Пока ticket ожидает решения, игрок остаётся на auth-сервере и видит chat/actionbar/bossbar. После `approve(ticketId)` безопасное продолжение автоматически завершает уже проверенную попытку и переводит игрока на backend. Повторно вводить пароль не нужно.

Ticket имеет TTL и привязан к игроку, имени, операции, фазе и generation сессии. Пароль, TOTP-код и recovery-коды не сохраняются. Старый ticket после переподключения не завершит новую сессию. Для отказа используется `deny(ticketId)`, а `VerificationResolvedEvent` сообщает результат.

Каждый display имеет строковый ID. Повторный вызов с тем же UUID и ID продолжает управлять существующим объектом, а не создаёт новый:

```java
BossBarHandle bar = kernel.display().bossBar(
    playerUuid,
    "social:verification",
    new BossBarOptions(
        "Ожидание Discord",
        1.0f,
        BossBarColor.PURPLE,
        BossBarOverlay.NOTCHED_10,
        false, false, false,
        Duration.ZERO // работает до явного удаления
    )
);

bar.progress(0.75f);
bar.text("Подтвердите вход");
bar.color(BossBarColor.YELLOW);
bar.animateProgress(0.0f, Duration.ofMinutes(5), Easing.LINEAR);

// Получить и продолжить управление из другого места:
kernel.display().findBossBar(playerUuid, "social:verification")
    .ifPresent(existing -> existing.color(BossBarColor.GREEN));

// Оба варианта отправляют реальное удаление клиенту:
bar.close();
kernel.display().removeBossBar(playerUuid, "social:verification");
```

`ActionBarHandle` позволяет менять текст, частоту повторной отправки и lifetime. `TitleHandle` управляет title/subtitle, fade-in, stay, fade-out и интервалом повтора. `BossBarHandle` управляет названием, progress, цветом, overlay, флагами, анимацией увеличения/уменьшения, паузой и lifetime. `Duration.ZERO` означает работу до явного `close/remove`.

Velocity использует Adventure API. BungeeCord отправляет обычный proxy `BossBar` packet напрямую через соединение игрока. Display API никак не связан с PicoLimbo; Limbo остаётся только отдельным сервером-маршрутом.

Обработчики событий вызываются в потоке, завершившем операцию pnAuth; для обращения к API конкретной платформы переключитесь на её scheduler.

Архитектурный поток выглядит так: нативное событие прокси → `AuthLifecycleCoordinator` → `AuthService`/политики → типизированное решение и domain event → тонкий адаптер BungeeCord или Velocity. Новые правила добавляются в `shared`, поэтому платформы не должны реализовывать их повторно.

Не передавайте наружу хеши паролей, TOTP-секреты или recovery-коды. Публичный `AuthUser` содержит только безопасные сведения об аккаунте.

### Platform and player API

Расширения получают игроков только через pnAuth и не зависят от классов Bungee, Velocity или Bukkit:

```java
PnPlatform platform = kernel.platform();

platform.player(playerId).ifPresent(player -> {
    player.sendMessage("Обычный текст");
    player.sendMessage(Component.text("Adventure component", NamedTextColor.GREEN));
    player.sendMessages(List.of(
        Component.text("Первая строка"),
        Component.text("Вторая строка")
    ));

    String ip = player.ipAddress();
    Optional<String> server = player.currentServer();
});
```

`PnPlayer` также предоставляет permissions, disconnect, display, dialogs и player-bound scheduler. На Folia player-bound задачи используют entity scheduler, а общие задачи — global region scheduler.

Именованные задачи хранятся в общем реестре. Повторная регистрация того же `owner + taskId + playerId` отменяет старую задачу:

```java
TaskHandle reminder = platform.tasks().repeating(
    "discord-extension",
    "verification-reminder",
    player,
    Duration.ZERO,
    Duration.ofSeconds(2),
    () -> player.sendMessage(Component.text("Подтвердите вход в Discord"))
);

platform.tasks().find("discord-extension", "verification-reminder", player.uniqueId());
platform.tasks().cancel("discord-extension", "verification-reminder", player.uniqueId());
platform.tasks().cancelAll("discord-extension");
```

При выходе игрока pnAuth автоматически закрывает его dialogs/display и отменяет все player-scoped tasks.

`PlayerDialog` повторяет нативную schema Minecraft: общий `DialogLayout`, типы `notice`, `confirmation`, `multi_action`, `server_links`, `dialog_list`; body `plain_message`/`item`; inputs `text`, `boolean`, `single_option`, `number_range`; static и dynamic actions, `exit_action`, columns/button width и все три `after_action`. `response()` ожидает первый ответ, а `onResponse(...)` принимает весь поток submit-событий для `after_action=none`.

На Paper 1.21.7+ pnAuth реально строит inline registry dialog, выполняет Vanilla `dialog show`, принимает `PlayerCustomClickEvent`, декодирует значения и поддерживает `dialog clear`. Более старые клиенты не объявляются native-capable: элементы формы никогда не отбрасываются молча.

Для standalone Paper/Folia раздел `paper` в `config.yml` задаёт auth-точку телепортации (`world`, `x/y/z`, `yaw/pitch`) и независимые ограничения `movement`, `chat`, `commands`, `interaction`, `breaking`, `placing`, `inventory`.

Один универсальный JAR содержит адаптеры BungeeCord, Velocity, Paper и Folia. Paper/Folia регистрируют тот же набор команд и aliases, что proxy-версии; мир, chat, inventory и посторонние команды блокируются до успешной авторизации. Базовая совместимость — Java 17 и Paper/Folia 1.20.4+, а нативные возможности более новых клиентов включаются через capability adapters.

## Сборка и проверка

```powershell
./gradlew.bat test
./gradlew.bat clean dist
```

Итоговый универсальный JAR появится в `build/dist/`.

### Тюнинг производительности

По умолчанию `AuthService` выполняет операции авторизации в пуле воркеров (минимум 2 потока, обычно = числу CPU).
Для больших сетей вы можете переопределить размер пула через системное свойство JVM:

```text
-Dpnauth.workerThreads=8
```
