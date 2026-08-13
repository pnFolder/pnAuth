# pnAuth

Общий плагин авторизации для BungeeCord и Velocity.

## Архитектура

- `shared` - API, auth lifecycle, PBKDF2/Bcrypt/Argon2, TOTP, recovery codes, sessions, IP limits, migrations and command service.
- `bungeecord` - исходники адаптера BungeeCord.
- `velocity` - исходники адаптера Velocity.
- `plugin` - единый сборочный модуль, который компилирует оба адаптера в один JAR.

Устанавливается один универсальный JAR. Для нескольких прокси укажите одну и ту же MySQL/MariaDB/PostgreSQL базу в конфигурации каждого прокси.

## Команды

- `/register <пароль> <повтор>`
- `/login <пароль>`
- `/logout`
- `/changepassword <старый> <новый>`
- `/unregister <пароль>`
- `/premium`
- `/totp <enable|verify|disable> [код]`
- `/status`
- `/auth <admin-подкоманда>`

Администраторские подкоманды требуют permissions `pnauth.admin.commands.*`:

- `/auth unregister <игрок>`
- `/auth changepassword <игрок> <пароль>`
- `/auth forcelogin <игрок>`
- `/auth forceregister <игрок> <пароль>`
- `/auth forcepremium <игрок>`

Административные команды работают из консоли и от имени игрока с соответствующим permission. Для регистрации аккаунта из консоли используйте `/auth forceregister <игрок> <пароль>` или `/auth register <игрок> <пароль>`.

До авторизации разрешены только команды авторизации. Чат также блокируется.

Чтобы неавторизованный игрок не мог попасть на игровой сервер, настройте:

```yaml
servers:
  require-auth-before-server: true
  auth-server: auth
```

Сервер `auth` должен существовать в конфигурации BungeeCord/Velocity.

## Конфигурация

После первого запуска создается `config.yml`:

```yaml
locale: ru
messages:
  # LEGACY, MINI_MESSAGE, JSON or PLAIN
  format: LEGACY
database:
  type: SQLITE
  file: auth.db
  mysql:
    host: 127.0.0.1
    port: 3306
    database: minecraft_auth
    username: ""
    password: ""
    use-ssl: false
    server-timezone: UTC
servers:
  auth-server: auth
  backend-server: hub
  require-auth-before-server: false
  forced-hosts:
    play.example.com: hub
    creative.example.com: creative
security:
  password:
    min-length: 6
    max-length: 64
    repeat-on-register: true
  login:
    max-attempts: 5
    lockout-seconds: 60
    ban-on-failed-login: true
    ban-seconds: 60
  hashing:
    algorithm: PBKDF2
    pbkdf2-iterations: 120000
    bcrypt-cost: 12
    argon2-iterations: 2
    argon2-memory-kb: 65536
    argon2-parallelism: 1
validation:
  username-pattern: "^[A-Za-z0-9_]{3,16}$"
access:
  block-chat: true
  unauthenticated-commands: [auth, pnauth, register, reg, login, l, logout, changepassword, changepass, totp, 2fa, premium, status]
features:
  premium:
    enabled: true
  session:
    lifetime-minutes: 60
    timeout-seconds: 60
   # First reminder and interval between reminders; 0 disables reminders
   reminder-seconds: 10
  totp:
    enabled: true
    max-attempts: 3
    lockout-seconds: 60
    issuer: "Minecraft Server"
    recovery-codes: 16
ui:
  dialogs:
    enabled: true
    fallback-to-commands: true
    allow-player-preference: true
    min-client-protocol: 771
  bossbar: true
  title: false
  actionbar: false
limbo:
  provider: pico
  enabled: false
  server-name: auth
  host: 127.0.0.1
  port: 25566
  auto-download: true
  download-base-url: "https://github.com/Quozul/PicoLimbo/releases/latest/download/"
  download-sha256: "1ba19f3ba52179a5eb20336bded8efa5f7967fea198927d1de49ebf190f3a527"
```

Перевод выбирается в основном конфиге:

```yaml
locale: ru
```

Формат сообщений выбирается в `messages.format`:

- `LEGACY` - классические `&a`/`§a` коды;
- `MINI_MESSAGE` - теги MiniMessage, например `<green>текст</green>`;
- `JSON` - Minecraft text component JSON;
- `PLAIN` - обычный текст без форматирования.

Встроенные переводы хранятся в legacy-виде и автоматически преобразуются в выбранный формат. При `MINI_MESSAGE` locale-файлы также поддерживают MiniMessage-теги, включая hover/click. Значения placeholders экранируются и не могут добавить форматирование.

Доступны `ru` и `en`. При первом запуске pnAuth создает редактируемые `plugins/pnAuth/messages/messages_ru.yml` и `messages_en.yml`. При обновлении плагина новые ключи добавляются автоматически, существующие пользовательские значения не перезаписываются. На BungeeCord 1.21.6+ доступна dialog-форма входа и регистрации.

TOTP-секреты хранятся в базе в зашифрованном виде. Ключ шифрования создается в `totp.key`, его нельзя удалять или публиковать.

При `limbo.enabled: true` pnAuth скачивает официальный PicoLimbo-бинарник с SHA-256 проверкой, запускает его, ждет готовности TCP-порта и регистрирует его как `server-name` на текущей платформе. Для работы лимбо `servers.auth-server` должен совпадать с `limbo.server-name`. На shutdown процесс корректно останавливается.

Поддерживаются `SQLITE`, `H2`, `MYSQL`, `MARIADB`, `POSTGRESQL` и произвольный `JDBC` URL.

Для общей базы:

```yaml
database:
  type: MYSQL
  mysql:
    host: 127.0.0.1
    port: 3306
    database: minecraft_auth
    username: "minecraft"
    password: "change-me"
    use-ssl: false
    server-timezone: UTC
```

## API

Общий контракт находится в `shared`:

```java
AuthApi api = plugin.getApi();
api.isAuthenticated(playerUuid);
api.login(playerUuid, password).thenAccept(result -> { });
```

В BungeeCord API доступен через `PnAuthBungeePlugin#getApi()`. В Velocity плагин можно получить через dependency injection и вызвать `PnAuthVelocityPlugin#getApi()`.

Пароли, соли и `AuthRecord` не выдаются публичным API. `AuthUser` содержит UUID, имя, даты, premium/TOTP-флаги и последний IP без секретов.

Платформенная командная граница состоит из четырех интерфейсов:

- `CommandService` описывает команды, выполнение и tab completion.
- `CommandContext` передает команду и аргументы.
- `CommandSource` абстрагирует игрока, консоль и permissions.
- `AuthPlatformBridge` получает только эффекты `AUTHENTICATED`, `LOGGED_OUT` и `ACCOUNT_DELETED`.

Поэтому shared не импортирует BungeeCord или Velocity. Платформенные классы только преобразуют native command/event API в эти интерфейсы.

Limbo имеет отдельную provider-границу:

- `LimboServer` описывает lifecycle и endpoint сервера.
- `LimboServerProvider` создает конкретную реализацию.
- `LimboServerRegistry` выбирает provider по `limbo.provider`.
- `PicoLimboProvider` является встроенной реализацией, но не частью auth routing-контракта.

Для миграции поддержаны схемы `TIAUTH`, `AUTHME`, `MCAUTH`, `LIMBOAUTH` и `NLOGIN` через `AuthMigrationService` или `/auth migrate`.

## Сборка

```powershell
./gradlew.bat clean dist
```

Это одна сборочная задача и один универсальный JAR:

- `build/dist/pnAuth-1.0.0.jar`

JAR содержит дескрипторы обеих платформ. BungeeCord использует `bungee.yml`, Velocity использует `velocity-plugin.json`.

Требуется Java 17 или новее.
