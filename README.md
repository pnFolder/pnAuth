# pnAuth

pnAuth — плагин авторизации для прокси BungeeCord и Velocity. Один JAR поддерживает обе платформы и включает регистрацию, вход, опциональные сессии по IP, 2FA/TOTP, recovery-коды, CAPTCHA, миграции, лимиты по IP и встроенный auth-limbo.

## Возможности

- Русский и английский интерфейс: `locale: ru` или `locale: en`.
- Пароли PBKDF2, BCrypt или Argon2id; PBKDF2-HMAC-SHA256 с 600 000 итераций используется по умолчанию.
- Двухфакторная аутентификация с зашифрованными TOTP-секретами и одноразовыми recovery-кодами.
- Ограничение неудачных попыток, временная блокировка и лимиты аккаунтов по IP.
- Command- и dialog-интерфейсы, CAPTCHA, title/actionbar-напоминания.
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
config-version: 5
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
