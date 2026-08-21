package ru.privatenull.pnauth.config

import net.elytrium.serializer.SerializerConfig
import net.elytrium.serializer.annotations.Comment
import net.elytrium.serializer.annotations.CommentValue
import net.elytrium.serializer.annotations.NewLine
import net.elytrium.serializer.annotations.Transient
import net.elytrium.serializer.language.`object`.YamlSerializable
import java.nio.file.Path

open class PnAuthYamlConfig(path: Path) : YamlSerializable(path, SERIALIZER) {

    @Comment(CommentValue("Версия схемы. Обновляется автоматически; не изменяйте вручную."))
    @JvmField
    var configVersion: Int = AuthConfig.CURRENT_SCHEMA_VERSION

    @Comment(
        CommentValue("Язык сообщений, создаваемых для игроков."),
        CommentValue("Поддерживаемые значения: ru, en.")
    )
    @JvmField
    var locale: String = "ru"

    @JvmField
    var messages: Messages = Messages()

    @JvmField
    var database: Database = Database()

    @JvmField
    var servers: Servers = Servers()

    @JvmField
    var security: Security = Security()

    @JvmField
    var validation: Validation = Validation()

    @JvmField
    var access: Access = Access()

    @JvmField
    var limits: Limits = Limits()

    @JvmField
    var features: Features = Features()

    @JvmField
    var ui: Ui = Ui()

    @JvmField
    var limbo: Limbo = Limbo()

    @JvmField
    var paper: Paper = Paper()

    @JvmField
    var externalVerification: ExternalVerification = ExternalVerification()

    @JvmField
    var cluster: Cluster = Cluster()

    @NewLine
    class Messages {
        @Comment(
            CommentValue("Формат сообщений: LEGACY, MINI_MESSAGE, JSON или PLAIN."),
            CommentValue("Он применяется к встроенному переводу и файлу messages_<locale>.yml.")
        )
        @JvmField
        var format: String = "LEGACY"
    }

    @NewLine
    class Database {
        @Comment(
            CommentValue("Тип хранилища: SQLITE, H2, MYSQL, MARIADB, POSTGRESQL или JDBC."),
            CommentValue("Для сети серверов используйте MYSQL, MARIADB или POSTGRESQL.")
        )
        @JvmField
        var type: String = "SQLITE"

        @Comment(CommentValue("Имя файла локальной SQLite/H2-базы относительно папки pnAuth."))
        @JvmField
        var file: String = "auth.db"

        @Comment(CommentValue("Полный JDBC URL. Если заполнен, имеет приоритет над готовыми настройками ниже."))
        @JvmField
        var url: String = ""

        @Comment(CommentValue("Пользователь для JDBC URL; для SQLite обычно оставляется пустым."))
        @JvmField
        var username: String = ""

        @Comment(CommentValue("Пароль для JDBC URL. Не публикуйте конфигурацию с этим значением."))
        @JvmField
        var password: String = ""

        @JvmField
        var mysql: Connection = Connection(3306)

        @JvmField
        var postgresql: Connection = Connection(5432)
    }

    class Connection @JvmOverloads constructor(
        @Comment(CommentValue("Порт SQL-сервера."))
        @JvmField var port: Int = 3306
    ) {
        @Comment(CommentValue("Адрес SQL-сервера."))
        @JvmField var host: String = "127.0.0.1"
        @Comment(CommentValue("Имя базы данных."))
        @JvmField var database: String = "minecraft_auth"
        @Comment(CommentValue("Пользователь SQL-базы."))
        @JvmField var username: String = ""
        @Comment(CommentValue("Пароль пользователя SQL-базы."))
        @JvmField var password: String = ""

        @Comment(CommentValue("Включает и проверяет TLS для удалённой SQL-базы. Отключайте только для локальной доверенной базы."))
        @JvmField var useSsl: Boolean = true

        @Comment(CommentValue("Часовой пояс SQL-сервера."))
        @JvmField var serverTimezone: String = "UTC"
    }

    @NewLine
    class Servers {
        @Comment(CommentValue("Имя сервера в конфигурации прокси, используемого до авторизации."))
        @JvmField var authServer: String = "auth"

        @Comment(CommentValue("Сервер, на который игрок попадёт после успешной авторизации."))
        @JvmField var backendServer: String = "hub"

        @Comment(CommentValue("Список основных серверов для балансировки; пустой список использует backend-server."))
        @JvmField var backendServers: List<String> = ArrayList()

        @Comment(CommentValue("Список auth-серверов для балансировки; пустой список использует auth-server."))
        @JvmField var authServers: List<String> = ArrayList()

        @Comment(CommentValue("Режим балансировки: LEAST_PLAYERS, FIRST_AVAILABLE, ROUND_ROBIN, RANDOM или FILLING."))
        @JvmField var balancerMode: String = "LEAST_PLAYERS"

        @Comment(CommentValue("Общий лимит игроков на сервер для режима FILLING."))
        @JvmField var maxPlayersPerServer: Int = 100

        @Comment(CommentValue("Индивидуальные лимиты серверов, например: hub-small: 50, hub-large: 250."))
        @JvmField var serverLimits: Map<String, Int> = LinkedHashMap()

        @Comment(CommentValue("Запрещает переход с auth-сервера до авторизации. Рекомендуется для публичной сети."))
        @JvmField var requireAuthBeforeServer: Boolean = true

        @Comment(CommentValue("Соответствие домена входа и конечного сервера."))
        @JvmField var forcedHosts: Map<String, String> = LinkedHashMap()
    }

    @NewLine
    class Security {
        @JvmField var password: Password = Password()
        @JvmField var login: Login = Login()
        @JvmField var hashing: Hashing = Hashing()

        class Password {
            @Comment(CommentValue("Минимальная и максимальная длина пароля включительно."))
            @JvmField var minLength: Int = 8

            @Comment(CommentValue("Максимальная допустимая длина пароля."))
            @JvmField var maxLength: Int = 64
            @Comment(CommentValue("Требует повторить пароль при регистрации для защиты от опечатки."))
            @JvmField var repeatOnRegister: Boolean = true
        }

        class Login {
            @Comment(CommentValue("Число неверных попыток до временной блокировки."))
            @JvmField var maxAttempts: Int = 5
            @Comment(CommentValue("Продолжительность блокировки после превышения попыток, в секундах."))
            @JvmField var lockoutSeconds: Int = 60
            @Comment(CommentValue("Включает временный IP-бан после серии неверных паролей."))
            @JvmField var banOnFailedLogin: Boolean = true
            @Comment(CommentValue("Продолжительность временного IP-бана, в секундах."))
            @JvmField var banSeconds: Int = 60
        }

        class Hashing {
            @Comment(
                CommentValue("Алгоритм хеширования: PBKDF2, BCRYPT или ARGON2."),
                CommentValue("PBKDF2 — безопасный и наиболее совместимый вариант по умолчанию.")
            )
            @JvmField var algorithm: String = "PBKDF2"

            @Comment(CommentValue("Число итераций PBKDF2-HMAC-SHA256; безопасное значение по умолчанию — 600000."))
            @JvmField var pbkdf2Iterations: Int = 600_000

            @Comment(CommentValue("Стоимость BCRYPT. Чем больше значение, тем медленнее перебор и вход."))
            @JvmField var bcryptCost: Int = 12
            @Comment(CommentValue("Число проходов ARGON2."))
            @JvmField var argon2Iterations: Int = 2
            @Comment(CommentValue("Память ARGON2 на одну проверку пароля, в KiB."))
            @JvmField var argon2MemoryKb: Int = 65_536
            @Comment(CommentValue("Число параллельных потоков ARGON2."))
            @JvmField var argon2Parallelism: Int = 1
        }
    }

    @NewLine
    class Validation {
        @Comment(CommentValue("Регулярное выражение для проверки ника Minecraft."))
        @JvmField var usernamePattern: String = "^[A-Za-z0-9_]{3,16}$"
    }

    @NewLine
    class Access {
        @Comment(CommentValue("Блокирует чат прокси до авторизации."))
        @JvmField var blockChat: Boolean = true

        @Comment(CommentValue("Команды, разрешённые до авторизации; указываются без начального слеша."))
        @JvmField var unauthenticatedCommands: List<String> = listOf(
            "auth", "pnauth", "register", "reg", "login", "l", "logout",
            "changepassword", "changepass", "totp", "2fa", "status"
        )
    }

    @NewLine
    class Limits {
        @Comment(CommentValue("Максимум одновременно подключённых аккаунтов с одного IP-адреса."))
        @JvmField var maxOnlineAccountsPerIp: Int = 10
        @Comment(CommentValue("Максимум зарегистрированных аккаунтов с одного IP-адреса."))
        @JvmField var maxRegisteredAccountsPerIp: Int = 10
        @Comment(CommentValue("IP-адреса, для которых лимиты аккаунтов не применяются."))
        @JvmField var excludedIps: List<String> = listOf("127.0.0.1")
    }

    @NewLine
    class Features {
        @JvmField var premium: Premium = Premium()
        @JvmField var session: Session = Session()
        @JvmField var totp: Totp = Totp()
        @JvmField var captcha: Captcha = Captcha()

        class Premium {
            @Comment(CommentValue("Разрешает доверенный автоматический вход для premium-аккаунтов."))
            @JvmField var enabled: Boolean = true
        }

        class Session {
            @Comment(
                CommentValue("Восстанавливает сессию без пароля при совпадении IP с прошлым входом."),
                CommentValue("По умолчанию выключено: общий NAT и VPN делают доверие только по IP небезопасным.")
            )
            @JvmField var restoreOnSameIp: Boolean = false

            @Comment(CommentValue("Срок действия восстановленной сессии после успешной авторизации, в минутах."))
            @JvmField var lifetimeMinutes: Int = 60

            @Comment(CommentValue("Через сколько секунд отключить игрока, если он не авторизовался."))
            @JvmField var timeoutSeconds: Int = 60

            @Comment(
                CommentValue("Интервал напоминаний об авторизации в секундах."),
                CommentValue("Первое напоминание приходит через этот интервал; 0 отключает напоминания.")
            )
            @JvmField var reminderSeconds: Int = 10
        }

        class Totp {
            @Comment(CommentValue("Включает двухфакторную авторизацию TOTP и команды /totp."))
            @JvmField var enabled: Boolean = true
            @Comment(CommentValue("Число неверных TOTP-кодов до блокировки."))
            @JvmField var maxAttempts: Int = 3
            @Comment(CommentValue("Продолжительность блокировки TOTP после превышения попыток, в секундах."))
            @JvmField var lockoutSeconds: Int = 60

            @Comment(CommentValue("Сколько секунд даётся на подтверждение новой настройки 2FA."))
            @JvmField var setupLifetimeSeconds: Int = 300

            @Comment(CommentValue("Название сервера в приложении-аутентификаторе."))
            @JvmField var issuer: String = "Minecraft Server"
            @Comment(CommentValue("Сколько одноразовых резервных кодов создавать игроку."))
            @JvmField var recoveryCodes: Int = 16
        }

        class Captcha {
            @Comment(CommentValue("Требует одноразовую кликабельную проверку перед показом формы пароля."))
            @JvmField var enabled: Boolean = false
            @Comment(CommentValue("Срок действия captcha-проверки, в секундах."))
            @JvmField var lifetimeSeconds: Int = 30
            @Comment(CommentValue("Число неверных кликов до создания новой captcha."))
            @JvmField var maxAttempts: Int = 3
        }
    }

    @NewLine
    class Ui {
        @JvmField var dialogs: Dialogs = Dialogs()
        @JvmField var processingTitle: ProcessingTitle = ProcessingTitle()
        @Comment(CommentValue("Включает обычные title-подсказки авторизации."))
        @JvmField var title: Boolean = false
        @Comment(CommentValue("Включает подсказки авторизации в actionbar."))
        @JvmField var actionbar: Boolean = false

        class Dialogs {
            @Comment(CommentValue("Включает нативные диалоговые окна новых клиентов Minecraft."))
            @JvmField var enabled: Boolean = true
            @Comment(CommentValue("Разрешает команды как запасной способ входа, если диалоги недоступны."))
            @JvmField var fallbackToCommands: Boolean = true
            @Comment(CommentValue("Разрешает игроку самостоятельно отключить диалоги."))
            @JvmField var allowPlayerPreference: Boolean = true
            @Comment(CommentValue("Минимальный protocol ID клиента, которому можно отправлять native dialog."))
            @JvmField var minClientProtocol: Int = 771
        }

        class ProcessingTitle {
            @Comment(CommentValue("Показывает анимированный title только во время фактической обработки пароля."))
            @JvmField var enabled: Boolean = true
            @JvmField var animation: Animation = Animation()
            @JvmField var timings: Timings = Timings()

            class Animation {
                @Comment(CommentValue("Тип анимации: NONE или GRADIENT."))
                @JvmField var type: String = "GRADIENT"
                @Comment(CommentValue("Цвета градиента в формате #RRGGBB; можно указать два и более цвета."))
                @JvmField var colors: List<String> = listOf("#d8b4fe", "#f0abfc", "#c4b5fd")

                @Comment(CommentValue("Количество кадров в одном бесшовном цикле градиента."))
                @JvmField var frameCount: Int = 12
            }

            class Timings {
                @Comment(CommentValue("Пауза между кадрами анимации, в миллисекундах."))
                @JvmField var frameIntervalMillis: Int = 120

                @Comment(CommentValue("Минимальное время показа индикатора; завершение авторизации при этом не задерживается."))
                @JvmField var minimumDisplayMillis: Int = 2500
                @Comment(CommentValue("Время появления title с результатом, в миллисекундах."))
                @JvmField var resultFadeInMillis: Int = 0

                @Comment(CommentValue("Сколько держать результат успеха или ошибки перед исчезновением."))
                @JvmField var resultDisplayMillis: Int = 1000
                @Comment(CommentValue("Время исчезновения title с результатом, в миллисекундах."))
                @JvmField var resultFadeOutMillis: Int = 500
                @Comment(CommentValue("Время появления обычного title, в миллисекундах."))
                @JvmField var fadeInMillis: Int = 0
                @Comment(CommentValue("Время показа обычного title, в миллисекундах."))
                @JvmField var stayMillis: Int = 5000
                @Comment(CommentValue("Время исчезновения обычного title, в миллисекундах."))
                @JvmField var fadeOutMillis: Int = 0
            }
        }
    }

    @NewLine
    class Limbo {
        @Comment(
            CommentValue("Провайдер встроенного limbo-сервера."),
            CommentValue("Встроенный провайдер: pico.")
        )
        @JvmField var provider: String = "pico"
        @Comment(CommentValue("Запускает встроенный limbo-сервер вместе с pnAuth."))
        @JvmField var enabled: Boolean = false
        @Comment(CommentValue("Имя маршрута limbo в конфигурации прокси."))
        @JvmField var serverName: String = "auth"
        @Comment(CommentValue("Адрес, на котором встроенный limbo принимает подключения."))
        @JvmField var host: String = "127.0.0.1"
        @Comment(CommentValue("Порт встроенного limbo-сервера."))
        @JvmField var port: Int = 25_566
        @Comment(CommentValue("Автоматически скачивает совместимую библиотеку PicoLimbo при отсутствии."))
        @JvmField var autoDownload: Boolean = true
        @Comment(CommentValue("Базовый URL загрузки PicoLimbo. Изменяйте только при использовании доверенного зеркала."))
        @JvmField var downloadBaseUrl: String = LimboSettings.OFFICIAL_DOWNLOAD_BASE_URL
        @Comment(CommentValue("SHA-256 ожидаемого файла PicoLimbo; защищает от подмены загрузки."))
        @JvmField var downloadSha256: String = LimboSettings.OFFICIAL_DOWNLOAD_SHA256
    }

    @NewLine
    class Paper {
        @Comment(CommentValue("Ограничения автономного Paper/Folia-сервера до авторизации."))
        @JvmField var teleport: Teleport = Teleport()
        @JvmField var restrictions: Restrictions = Restrictions()

        class Teleport {
            @Comment(CommentValue("Телепортирует неавторизованного игрока в указанную точку."))
            @JvmField var enabled: Boolean = false
            @Comment(CommentValue("Название мира для точки ожидания."))
            @JvmField var world: String = "world"
            @Comment(CommentValue("Координата X точки ожидания."))
            @JvmField var x: Double = 0.5
            @Comment(CommentValue("Координата Y точки ожидания."))
            @JvmField var y: Double = 100.0
            @Comment(CommentValue("Координата Z точки ожидания."))
            @JvmField var z: Double = 0.5
            @Comment(CommentValue("Горизонтальный угол взгляда игрока."))
            @JvmField var yaw: Float = 0.0f
            @Comment(CommentValue("Вертикальный угол взгляда игрока."))
            @JvmField var pitch: Float = 0.0f
        }

        class Restrictions {
            @Comment(CommentValue("Блокирует перемещение до авторизации."))
            @JvmField var movement: Boolean = true
            @Comment(CommentValue("Блокирует чат до авторизации."))
            @JvmField var chat: Boolean = true
            @Comment(CommentValue("Блокирует команды, кроме разрешённых auth-команд."))
            @JvmField var commands: Boolean = true
            @Comment(CommentValue("Блокирует взаимодействие с сущностями и блоками."))
            @JvmField var interaction: Boolean = true
            @Comment(CommentValue("Блокирует разрушение блоков."))
            @JvmField var breaking: Boolean = true
            @Comment(CommentValue("Блокирует установку блоков."))
            @JvmField var placing: Boolean = true
            @Comment(CommentValue("Блокирует работу с инвентарём."))
            @JvmField var inventory: Boolean = true
        }
    }

    @NewLine
    class ExternalVerification {
        @Comment(
            CommentValue("Включает дополнительное подтверждение опасных действий через внешний мессенджер."),
            CommentValue("После изменения на true обязательно настройте HTTPS public-url и хотя бы одного провайдера.")
        )
        @JvmField var enabled: Boolean = false

        @Comment(
            CommentValue("Действия, которым требуется внешнее подтверждение."),
            CommentValue("Допустимые значения: LOGIN, REGISTER, CHANGE_PASSWORD, UNREGISTER, TOTP_DISABLE, PREMIUM_CHANGE.")
        )
        @JvmField var operations: List<String> = listOf("LOGIN")

        @Comment(CommentValue("Сколько секунд действуют кнопки подтверждения и отклонения."))
        @JvmField var lifetimeSeconds: Int = 300

        @JvmField var callback: Callback = Callback()
        @JvmField var discord: Discord = Discord()
        @JvmField var telegram: Telegram = Telegram()
        @JvmField var vk: Vk = Vk()
        @JvmField var custom: Custom = Custom()

        class Callback {
            @Comment(CommentValue("Локальный адрес HTTP-сервера. 127.0.0.1 безопасен при использовании reverse proxy."))
            @JvmField var host: String = "127.0.0.1"

            @Comment(CommentValue("Локальный порт обработчика одноразовых ссылок."))
            @JvmField var port: Int = 8765

            @Comment(
                CommentValue("Публичный HTTPS-адрес, направленный reverse proxy на host:port."),
                CommentValue("Пример: https://auth.example.com")
            )
            @JvmField var publicUrl: String = "https://auth.example.com"
        }

        class Discord {
            @Comment(CommentValue("Отправляет запросы подтверждения в Discord через webhook."))
            @JvmField var enabled: Boolean = false
            @Comment(CommentValue("URL входящего webhook Discord. Храните его в секрете."))
            @JvmField var webhookUrl: String = ""
        }

        class Telegram {
            @Comment(CommentValue("Отправляет запросы подтверждения через Telegram-бота."))
            @JvmField var enabled: Boolean = false
            @Comment(CommentValue("Токен Telegram-бота от BotFather. Храните его в секрете."))
            @JvmField var botToken: String = ""
            @Comment(CommentValue("ID пользователя, группы или канала, куда отправлять запросы."))
            @JvmField var chatId: String = ""
        }

        class Vk {
            @Comment(CommentValue("Отправляет запросы подтверждения от имени сообщества VK."))
            @JvmField var enabled: Boolean = false
            @Comment(CommentValue("Токен сообщества VK с правом отправки сообщений. Храните его в секрете."))
            @JvmField var accessToken: String = ""
            @Comment(CommentValue("peer_id получателя или беседы VK."))
            @JvmField var peerId: String = ""
            @Comment(CommentValue("Версия VK API."))
            @JvmField var apiVersion: String = "5.199"
        }

        class Custom {
            @Comment(CommentValue("Отправляет подписанные запросы в собственный HTTPS-сервис."))
            @JvmField var enabled: Boolean = false
            @Comment(CommentValue("HTTPS endpoint, принимающий событие verification.requested версии v1."))
            @JvmField var url: String = "https://auth.example.com/integrations/pnauth"
            @Comment(
                CommentValue("Общий HMAC-секрет минимум из 32 символов."),
                CommentValue("Рекомендуется: ${'$'}{ENV:PNAUTH_CUSTOM_PROVIDER_SECRET}")
            )
            @JvmField var secret: String = ""
        }
    }

    @NewLine
    class Cluster {
        @Comment(
            CommentValue("Режим сети: STANDALONE, SHARED_DATABASE, REDIS или HUB."),
            CommentValue("STANDALONE подходит одному серверу; SHARED_DATABASE — небольшой сети с общей SQL-базой."),
            CommentValue("Redis хранит только события синхронизации, но никогда не пароли и не их хеши.")
        )
        @JvmField var mode: String = "STANDALONE"

        @Comment(CommentValue("Уникальное имя этого прокси или сервера в сети pnAuth."))
        @JvmField var nodeId: String = "server-1"

        @JvmField var redis: Redis = Redis()
        @JvmField var hub: Hub = Hub()

        class Redis {
            @Comment(
                CommentValue("Адрес Redis. Для удалённого подключения используйте rediss:// с TLS."),
                CommentValue("Секрет можно взять из окружения: ${'$'}{ENV:PNAUTH_REDIS_URI}.")
            )
            @JvmField var uri: String = "redis://127.0.0.1:6379"

            @Comment(CommentValue("Имя Redis Stream для событий pnAuth."))
            @JvmField var stream: String = "pnauth:events"

        }

        class Hub {
            @Comment(CommentValue("Публичный HTTPS-адрес центрального pnAuth Hub."))
            @JvmField var url: String = "https://auth.example.com"

            @Comment(CommentValue("Идентификатор этого узла, зарегистрированный в Hub."))
            @JvmField var clientId: String = ""

            @Comment(
                CommentValue("Секрет узла. Рекомендуется только ссылка на переменную окружения:"),
                CommentValue("${'$'}{ENV:PNAUTH_HUB_CLIENT_SECRET}")
            )
            @JvmField var clientSecret: String = ""

            @Comment(CommentValue("Таймаут подключения к Hub, в миллисекундах."))
            @JvmField var connectTimeoutMillis: Int = 5000
        }
    }

    companion object {
        @Transient
        private val SERIALIZER: SerializerConfig = SerializerConfig.Builder()
            .setCommentValueIndent(1)
            .build()
    }
}
