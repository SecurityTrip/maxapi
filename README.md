# maxapi

[![CI](https://github.com/SecurityTrip/maxapi/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/SecurityTrip/maxapi/actions/workflows/ci.yml)
[![Release](https://github.com/SecurityTrip/maxapi/actions/workflows/release.yml/badge.svg?event=release)](https://github.com/SecurityTrip/maxapi/actions/workflows/release.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.securitytrip/maxapi?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.securitytrip/maxapi)
[![Line coverage](https://img.shields.io/badge/line%20coverage-100%25-brightgreen)](#сборка-и-тесты)
[![API reference](https://img.shields.io/badge/docs-API%20reference-blue)](https://securitytrip.github.io/maxapi/)
[![License](https://img.shields.io/github/license/SecurityTrip/maxapi)](LICENSE)

**Русский** | [English](README.en.md)

Библиотека на Kotlin и Java для ботов мессенджера [Max](https://max.ru) на основе
[Max Bot API](https://dev.max.ru/docs-api).

Она состоит из двух слоёв, которые можно использовать по отдельности:

- **Клиент API** (`MaxClient`): все методы Bot API (спецификация 0.0.33) с типизированными моделями.
  В Kotlin это `suspend`-функции, в Java — блокирующий фасад и фасад на `CompletableFuture`.
- **Фреймворк для ботов** (`MaxBot`): обработчики команд, сообщений и нажатий кнопок, long polling
  с сохранением порядка внутри чата и обработчик вебхуков для любого HTTP-сервера.

```kotlin
val bot = MaxBot(System.getenv("MAX_BOT_TOKEN"))
bot.onCommand("start") { reply("Hello, ${user?.firstName}!") }
bot.onMessage { replyToMessage(text ?: "I only understand text") }
bot.startPolling()
```

## Содержание

- [Возможности](#возможности)
- [Требования и установка](#требования-и-установка)
- [Совместимость](#совместимость)
- [Получение токена](#получение-токена)
- [Быстрый старт](#быстрый-старт)
- [Фреймворк для ботов](#фреймворк-для-ботов)
  - [Обработчики и маршрутизация](#обработчики-и-маршрутизация)
  - [Контексты обработчиков](#контексты-обработчиков)
  - [Команды](#команды)
  - [Кнопки и нажатия](#кнопки-и-нажатия)
  - [Ссылки с параметром](#ссылки-с-параметром)
  - [Другие события](#другие-события)
  - [Фильтры](#фильтры)
  - [Middleware](#middleware)
  - [Роутеры](#роутеры)
  - [Состояние диалога](#состояние-диалога)
  - [Сцены и мастера](#сцены-и-мастера)
  - [Клавиатуры: страницы и подтверждение](#клавиатуры-страницы-и-подтверждение)
  - [Переводы](#переводы)
  - [Реестр чатов](#реестр-чатов)
  - [Рассылки](#рассылки)
  - [Обработка ошибок](#обработка-ошибок)
  - [Long polling](#long-polling)
  - [Вебхуки](#вебхуки)
- [Клиент API](#клиент-api)
  - [Вызов методов](#вызов-методов)
  - [Клиент в Java](#клиент-в-java)
  - [Сборка сообщений](#сборка-сообщений)
  - [Форматирование](#форматирование)
  - [Загрузка файлов](#загрузка-файлов)
  - [Постраничная выдача](#постраничная-выдача)
- [Spring Boot](#spring-boot)
  - [Health-индикатор](#health-индикатор)
  - [Нативный образ](#нативный-образ)
- [Ktor](#ktor)
- [Мини-приложения](#мини-приложения)
  - [Проверка initData](#проверка-initdata)
  - [Телефон из requestContact](#телефон-из-requestcontact)
  - [Контакт от кнопки request_contact](#контакт-от-кнопки-request_contact)
  - [Диплинки](#диплинки)
  - [Бэкенд на Spring MVC](#бэкенд-на-spring-mvc)
  - [Бэкенд на Ktor](#бэкенд-на-ktor)
- [Тестирование ботов](#тестирование-ботов)
- [Модели](#модели)
- [Ошибки и повторы](#ошибки-и-повторы)
- [Настройка](#настройка)
- [Надёжность и мониторинг](#надёжность-и-мониторинг)
- [Хранилища](#хранилища)
- [Потоки и жизненный цикл](#потоки-и-жизненный-цикл)
- [Структура проекта](#структура-проекта)
- [Сборка и тесты](#сборка-и-тесты)
- [Выпуск релизов](#выпуск-релизов)
- [Участие в разработке](#участие-в-разработке)
- [Лицензия](#лицензия)

## Возможности

- Все 33 метода спецификации названы по их `operationId`, поэтому
  [официальная документация](https://dev.max.ru/docs-api) применима напрямую.
- Типизированные неизменяемые модели. События, вложения, кнопки и разметка текста — запечатанные
  иерархии: `when (update) { is MessageCreatedUpdate -> ... }` в Kotlin или `instanceof` в Java.
- Совместимость с будущими версиями API: неизвестные библиотеке типы событий, вложений, кнопок и
  разметки разбираются в объекты `Unknown*` со всеми полями, а неизвестные значения перечислений
  становятся `UNKNOWN`. Новые возможности сервера не ломают разбор ответов.
- Полноценная поддержка Java: блокирующий фасад и фасад на `CompletableFuture`, билдеры, фабрики
  `@JvmStatic`, перегрузки для необязательных параметров, только непроверяемые исключения.
- Kotlin DSL для сообщений и клавиатур.
- Строительные блоки бота: комбинируемые фильтры, middleware, роутеры для модулей, состояния
  диалогов со сменным хранилищем, типизированные payload кнопок, меню команд из обработчиков.
- Инструменты для ботов: сцены-мастера с проверкой ответов, шагом назад и отменой; постраничные
  клавиатуры и «Да / Нет»; переводы по локали пользователя; реестр чатов бота; рассылки в пределах
  лимитов Max, которые продолжаются после перезапуска.
- Загрузка файлов одним вызовом; токен бота никогда не отправляется на хост загрузки.
- Автоматические повторы с экспоненциальной задержкой при 429/503, а для идемпотентных запросов —
  и при 502/504 и сетевых ошибках; повторная отправка, пока вложение ещё обрабатывается
  (`attachment.not.ready`).
- Правила платформы: лимиты Max (длина текста, размер клавиатуры, кнопки, команды) проверяются до
  отправки, длинный текст уходит несколькими сообщениями без поломки разметки, `RateLimit.MAX`
  соблюдает лимиты частоты запросов, а стартер возобновляет подписку на вебхук, если Max её снял.
- Бэкенд мини-приложений: проверка `initData`, телефона из `requestContact()` и контакта от кнопки
  `request_contact` по формулам Max, построители диплинков, аргумент `@MaxWebAppUser` для Spring MVC
  и плагин `MaxWebApp` для Ktor.
- Long polling с ограниченной параллельностью, порядком внутри чата и обратным давлением.
- Обработчик вебхуков с проверкой секрета за постоянное время, который подключается к Spring, Ktor,
  Javalin или сервлетам.
- Мало зависимостей: OkHttp, Jackson, kotlinx.coroutines. Логи пишутся в `System.Logger` и через
  обычные мосты попадают в SLF4J, Log4j или JUL.
- Стартер Spring Boot (polling или вебхук из `application.yaml`, на Spring MVC или WebFlux),
  маршрутизация вебхуков для Ktor, тестовый набор, который запускает обработчики без сети, и
  хранилища в SQL или Redis.
- Эксплуатация в продакшене: health-индикатор для Actuator, метрики Micrometer, трассировка
  OpenTelemetry, в которой спан события текущий в его обработчике, и метаданные рефлексии для
  нативных образов GraalVM.

## Требования и установка

- JDK 17 или новее.
- Kotlin 2.x, если библиотека используется из Kotlin.

Библиотека опубликована в Maven Central в группе `io.github.securitytrip` (последняя версия — на
странице [релизов](https://github.com/SecurityTrip/maxapi/releases)):

| Артефакт                     | Содержимое                                                     |
|------------------------------|----------------------------------------------------------------|
| `maxapi`                     | клиент API и фреймворк для ботов                               |
| `maxapi-spring-boot-starter` | автоконфигурация Spring Boot, см. [Spring Boot](#spring-boot)  |
| `maxapi-ktor`                | маршрутизация вебхуков в Ktor, см. [Ktor](#ktor)               |
| `maxapi-test`                | тестовый набор, см. [Тестирование ботов](#тестирование-ботов)  |
| `maxapi-micrometer`          | метрики Micrometer, см. [Надёжность и мониторинг](#надёжность-и-мониторинг) |
| `maxapi-jdbc`                | состояния диалогов, marker и обработанные события в SQL-базе, см. [Хранилища](#хранилища) |
| `maxapi-redis`               | то же в Redis (Lettuce), см. [Хранилища](#хранилища)           |
| `maxapi-opentelemetry`       | трассировка OpenTelemetry, см. [Надёжность и мониторинг](#надёжность-и-мониторинг) |
| `maxapi-bom`                 | версии всех артефактов выше                                    |

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("io.github.securitytrip:maxapi-bom:<version>"))
    implementation("io.github.securitytrip:maxapi")
    testImplementation("io.github.securitytrip:maxapi-test")
}
```

```xml
<!-- pom.xml -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.securitytrip</groupId>
            <artifactId>maxapi-bom</artifactId>
            <version><!-- version --></version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.securitytrip</groupId>
        <artifactId>maxapi</artifactId>
    </dependency>
</dependencies>
```

OkHttp, Jackson и kotlinx.coroutines подключаются транзитивно. Пакеты в коде —
`ru.securitytrip.maxapi.*`.

Чтобы попробовать ещё не выпущенные изменения, установите текущие исходники в локальный
Maven-репозиторий командой `./gradlew publishToMavenLocal` и подключите `-SNAPSHOT`-версию из
[maxapi-publishing](build-logic/src/main/kotlin/maxapi.publishing.gradle.kts), добавив
`mavenLocal()` в репозитории.

## Совместимость

С версии 1.0 публичный API следует [семантическому версионированию](https://semver.org/lang/ru/):
минорные и патч-версии сохраняют совместимость по исходному коду и бинарную совместимость, а API
сначала помечается устаревшим (с `ReplaceWith`) и удаляется только в следующей мажорной версии.
Публичный API каждого модуля записан в `<module>/api/` и проверяется при каждой сборке. Переходите
с 0.x? См. [MIGRATION.md](MIGRATION.md).

| Компонент         | Поддержка                                                      |
|-------------------|----------------------------------------------------------------|
| Java              | 17 или новее                                                   |
| Kotlin            | 2.x (библиотека собирается на 2.4)                             |
| Max Bot API       | спецификация 0.0.33; новые возможности сервера приходят как объекты `Unknown*` |
| Spring Boot       | 4.x (собирается и тестируется на 4.1)                          |
| Ktor              | 3.x (собирается и тестируется на 3.6)                          |
| Micrometer        | 1.x (собирается и тестируется на 1.17)                         |
| OpenTelemetry     | API 1.x (собирается и тестируется на 1.62)                     |
| GraalVM native    | метаданные в jar `maxapi`, подсказки Spring AOT в стартере; см. [Нативный образ](#нативный-образ) |
| Lettuce (Redis)   | 7.x (собирается и тестируется на 7.5 с Redis 7.4)              |
| JDBC              | любая база со стандартным SQL (тестируется на H2 2.4 и PostgreSQL 17) |

В совместимый API не входят: всё `internal`, текст сообщений исключений и логов, а также точные
ответы `BotTester` по умолчанию.

## Получение токена

Создайте бота на [business.max.ru](https://business.max.ru/) и скопируйте его токен доступа.
Библиотека передаёт его в заголовке `Authorization` каждого запроса. Не храните токен в исходном
коде: примеры читают его из переменной окружения `MAX_BOT_TOKEN`. `MaxClientConfig.toString()`
никогда не выводит токен.

## Быстрый старт

### Kotlin

```kotlin
import ru.securitytrip.maxapi.bot.MaxBot

suspend fun main() {
    MaxBot(System.getenv("MAX_BOT_TOKEN")).use { bot ->
        bot.onCommand("start") {
            reply {
                text("Pick an option:")
                keyboard {
                    row { callback("👍 Like", "like"); callback("👎 Dislike", "dislike") }
                }
            }
        }
        bot.onCallback("like") { answer("Thanks!") }
        bot.onCallback("dislike") { answer("Sorry to hear that") }
        bot.onMessage { replyToMessage(text ?: "I only understand text") }

        bot.startPolling() // работает, пока корутину не отменят
    }
}
```

### Java

```java
import ru.securitytrip.maxapi.bot.MaxBot;
import ru.securitytrip.maxapi.dsl.Buttons;
import ru.securitytrip.maxapi.model.message.NewMessageBody;

public class Main {
    public static void main(String[] args) {
        try (MaxBot bot = new MaxBot(System.getenv("MAX_BOT_TOKEN"))) {
            bot.onCommand("start", ctx -> ctx.replyBlocking(NewMessageBody.builder()
                    .text("Pick an option:")
                    .keyboardRow(Buttons.callback("👍 Like", "like"), Buttons.callback("👎 Dislike", "dislike"))
                    .build()));
            bot.onCallback("like", ctx -> ctx.answerBlocking("Thanks!"));
            bot.onCallback("dislike", ctx -> ctx.answerBlocking("Sorry to hear that"));
            bot.onMessage(ctx -> ctx.replyToMessageBlocking(ctx.getText() != null ? ctx.getText() : "I only understand text"));

            bot.startBlocking(); // блокирует поток до bot.stop() или фатальной ошибки
        }
    }
}
```

Полные запускаемые версии лежат в [examples](examples).

## Фреймворк для ботов

### Обработчики и маршрутизация

Обработчики регистрируются методами `on*` у `MaxBot`:

| Метод                          | Что обрабатывает                                           | Контекст             |
|--------------------------------|------------------------------------------------------------|----------------------|
| `onCommand("name")`            | сообщения, начинающиеся с `/name`                          | `MessageContext`     |
| `onCommand("name", "description")` | то же и добавляет команду в меню бота                  | `MessageContext`     |
| `onMessage { }`                | любое новое сообщение                                      | `MessageContext`     |
| `onMessage(filter) { }`        | новые сообщения, подходящие под условие                    | `MessageContext`     |
| `onCallback("payload") { }`    | нажатия кнопок с точно таким payload                       | `CallbackContext`    |
| `onCallback(filter) { }`       | нажатия кнопок, подходящие под условие                     | `CallbackContext`    |
| `onCallback(callbackData) { }` | нажатия кнопок с payload в формате `CallbackData`          | `CallbackContext`    |
| `onCallback { }`               | любое нажатие кнопки                                       | `CallbackContext`    |
| `onBotStarted { }`             | пользователь нажал «Начать» или открыл ссылку с параметром | `BotStartedContext`  |
| `on<T> { }` / `onUpdate(T)`    | любое событие типа `T`; `Update` подходит под всё          | `UpdateContext<T>`   |
| `onError { error, update -> }` | исключения из обработчиков и ошибки polling                | —                    |
| `use { ctx, next -> }`         | middleware вокруг обработчиков                             | любой                |
| `include(router)`              | добавляет обработчики `Router`                             | —                    |

**Обработчики проверяются в порядке регистрации, и выполняется только первый подходящий.**
Регистрируйте частные обработчики раньше общих:

```kotlin
bot.onCommand("start") { ... }                        // 1. /start
bot.onMessage({ it.text == "ping" }) { reply("pong") } // 2. точный текст
bot.onMessage { ... }                                  // 3. всё остальное
```

События, для которых нет подходящего обработчика, игнорируются. Бот запрашивает у сервера только
те типы событий, для которых есть обработчики, — это экономит трафик.

У каждого метода регистрации две перегрузки:

- Kotlin: `suspend`-лямбда с контекстом в качестве получателя: `bot.onCommand("start") { reply("Hi") }`;
- Java: `Handler<Context>`, который выполняется на `Dispatchers.IO`, поэтому блокирующие вызовы в
  нём допустимы: `bot.onCommand("start", ctx -> ctx.replyBlocking("Hi"))`.

Методы регистрации возвращают бота, так что вызовы можно объединять в цепочку. Регистрируйте все
обработчики до запуска polling или приёма вебхуков.

### Контексты обработчиков

Обработчик получает контекст с событием и короткими способами на него ответить.

`UpdateContext<U>` (база всех контекстов):

| Член                           | Описание                                                              |
|--------------------------------|-----------------------------------------------------------------------|
| `update`                       | обрабатываемое событие                                                |
| `bot`, `client`, `blocking()`  | бот, его `MaxClient` и блокирующий фасад                              |
| `chatId`, `user`               | чат и пользователь события, если известны                             |
| `locale`                       | язык клиента пользователя (`ru`, `en`, ...), если он есть в событии   |
| `state`, `stateData`           | состояние диалога и его значения, см. [Состояние диалога](#состояние-диалога) |
| `setState`, `updateStateData`, `clearState` | изменить состояние диалога                               |
| `reply(text)`, `reply(body)`, `reply { DSL }` | отправить сообщение в чат (или пользователю, если чата нет) |
| `replyLong(text)`, `replyLong(body)`, `replyLong { DSL }` | то же для текста любой длины, см. [Длинный текст](#длинный-текст) |
| `sendAction(action)`, `typing()` | показать «печатает…» или другое действие                           |
| `replyBlocking`, `replyLongBlocking`, `sendActionBlocking`, `setStateBlocking`, ... | версии для Java |

`MessageContext` добавляет `message`, `text`, `messageId`, `command`, `commandArgs`,
`replyToMessage(...)` (ответ с цитатой) и `replyToMessageBlocking(...)`. У сообщений без текста
`text` равен `null`: для фото и стикеров Max присылает пустую строку, которую `text` превращает в
`null`, поэтому `text ?: "..."` и `Filters.hasText()` работают как ожидается.

`CallbackContext` добавляет `callback`, `payload`, `message` (сообщение с клавиатурой),
`data(callbackData)`, `answer(notification)`, `answer(CallbackAnswer)`,
`editMessage(body, notification)` и их версии `*Blocking`.

`BotStartedContext` добавляет `payload` — параметр из ссылки.

Любой метод API доступен через `client`, например `client.pinMessage(chatId!!, messageId!!)`.

### Команды

`onCommand("start")` срабатывает на `/start`, `/Start` и `/start с аргументами`; ведущий `/` в
имени необязателен. Команда с суффиксом, например `/start@my_bot`, обрабатывается, только если
суффикс — имя этого бота, так что в групповых чатах команды для других ботов не трогаются. Имя бота
запрашивается через `getMyInfo` при первой такой команде; задайте `bot.username`, чтобы обойтись
без этого вызова.

```kotlin
bot.onCommand("weather") {
    val city = commandArgs.ifBlank { "Moscow" } // "/weather Kazan" -> "Kazan"
    reply("Weather in $city: ...")
}
```

Команды, зарегистрированные с описанием, составляют меню бота. `syncCommands()` отправляет их
(включая команды подключённых роутеров) через `editMyCommands`; вызывайте его один раз при запуске:

```kotlin
bot.onCommand("start", "Start the bot") { ... }
bot.onCommand("weather", "Weather forecast") { ... }
bot.syncCommands() // bot.commands() возвращает список
```

Меню показывают мобильные приложения Max; веб-клиент его не показывает, там пользователи вводят
команды сами.

### Кнопки и нажатия

Клавиатуры прикрепляются к сообщениям. Виды кнопок:

| Фабрика (`Buttons.*` / DSL строки)    | Что происходит при нажатии                             |
|---------------------------------------|--------------------------------------------------------|
| `callback(text, payload)`             | бот получает нажатие с `payload`                       |
| `link(text, url)`                     | пользователь открывает `url`                           |
| `message(text)`                       | пользователь отправляет `text` в чат                   |
| `requestContact(text)`                | пользователь делится контактом (`ContactAttachment`)   |
| `requestGeoLocation(text, quick)`     | пользователь делится местоположением (`LocationAttachment`) |
| `openApp(text, webApp, payload)`      | открывается мини-приложение                            |
| `clipboard(text, payload)`            | `payload` копируется в буфер обмена                    |

```kotlin
bot.onCommand("menu") {
    reply {
        text("Main menu")
        keyboard {
            row { callback("Orders", "orders"); callback("Settings", "settings") }
            row { link("Website", "https://example.com") }
        }
    }
}

// Отвечайте на каждое нажатие: измените сообщение, покажите уведомление или и то и другое.
bot.onCallback("orders") { editMessage(message { text("You have no orders yet") }) }
bot.onCallback("settings") { answer("Settings are coming soon") }

// Payload с параметрами
bot.onCallback(Filters.payloadStartsWith("order:")) {
    val id = payload!!.removePrefix("order:")
    answer("Order $id")
}
```

`CallbackData` описывает такие payload один раз, упаковывает и разбирает их и проверяет лимит API
в 1024 символа:

```kotlin
val order = CallbackData("order", "id", "action")    // payload вида "order:42:buy"

reply { text("Order 42"); keyboard { row { callback("Buy", order.pack(42, "buy")) } } }

bot.onCallback(order) {
    val fields = data(order)                         // {id=42, action=buy}
    answer("Order ${fields["id"]}: ${fields["action"]}")
}
```

```java
CallbackData order = new CallbackData("order", "id", "action");
bot.onCallback(order, ctx -> ctx.answerBlocking("Order " + ctx.data(order).get("id")));
```

Уведомление (`answer(notification)`) — короткое всплывающее сообщение в мобильных приложениях Max;
веб-клиент его не показывает. Если пользователь должен увидеть результат, измените и сообщение
через `editMessage(body, notification)` или отправьте новое.

В Java строки клавиатуры добавляются через `keyboardRow(...)`:

```java
NewMessageBody.builder()
        .text("Main menu")
        .keyboardRow(Buttons.callback("Orders", "orders"), Buttons.callback("Settings", "settings"))
        .keyboardRow(Buttons.link("Website", "https://example.com"))
        .build();
```

### Ссылки с параметром

Ссылка `https://max.ru/<botName>?start=<payload>` (deep link) открывает диалог с ботом и передаёт
параметр (до 128 символов) в событии `bot_started`. Такую ссылку строит
`MaxLinks.botStart("shop_bot", "ref-42")`, см. [Диплинки](#диплинки).

```kotlin
bot.onBotStarted {
    when (val ref = payload) {
        null -> reply("Welcome!")
        else -> reply("Welcome! You came from $ref")
    }
}
```

### Другие события

У каждого типа события свой класс в `ru.securitytrip.maxapi.model.update`. Любой из них
обрабатывается через `on<T>`:

```kotlin
bot.on<BotAddedToChatUpdate> { reply("Hi everyone! Thanks for adding me, ${update.user.firstName}") }
bot.on<MessageEditedUpdate> { println("Edited: ${update.message.body?.text}") }
bot.on<UserAddedToChatUpdate> { reply("Welcome, ${update.user.firstName}!") }
```

```java
bot.onUpdate(BotAddedToChatUpdate.class, ctx -> ctx.replyBlocking("Hi everyone!"));
```

В групповых чатах бот получает сообщения и события участников, только если он администратор с
правом `read_all_messages`.

### Фильтры

В `Filters` собраны готовые условия для методов `on*`, принимающих фильтр. В Kotlin они
объединяются через `and`, `or` и `!`, в Java — методами `Predicate`: `and`, `or` и `negate`.

| Фильтр                                           | Что подходит                                    |
|--------------------------------------------------|-------------------------------------------------|
| `hasText()`, `text(Regex)`, `text(Pattern)`      | сообщения с текстом, текст с совпадением        |
| `textEquals(...)`, `textEqualsIgnoreCase(...)`, `textStartsWith(prefix)` | текст сообщения         |
| `chatType(ChatType.DIALOG, ...)`                 | сообщения в диалогах, группах или каналах       |
| `hasAttachment<PhotoAttachment>()`, `hasAttachment(Class)` | сообщения с вложением нужного типа    |
| `payloadStartsWith(prefix)`                      | нажатия кнопок                                  |
| `fromUsers(ids)`, `inChats(ids)`                 | любые события от этих пользователей или из этих чатов |
| `state(names)`, `noState()`, `anyState()`        | любые события, по [состоянию диалога](#состояние-диалога) |
| `Filters.of { ... }`                             | своё условие                                    |

```kotlin
bot.onMessage(Filters.chatType(ChatType.DIALOG) and Filters.hasAttachment<PhotoAttachment>()) { reply("Nice photo!") }
bot.onMessage(Filters.fromUsers(*admins) and Filters.textStartsWith("/admin")) { ... }
```

```java
// В Java начинайте с самого частного фильтра: and() принимает фильтры того же или более общего контекста.
bot.onMessage(Filters.chatType(ChatType.DIALOG).and(Filters.fromUsers(42L)), ctx -> ...);
```

Фильтры любых событий (`fromUsers`, `state`, ...) подходят для `onMessage`, `onCallback` и
остальных. Везде, где принимается фильтр, можно передать лямбду:
`bot.onMessage({ (it.text?.length ?: 0) > 100 }) { ... }`.

### Middleware

Middleware выполняется вокруг обработчиков: логирование, замер времени, проверка доступа,
преобразование ошибок. Оно получает контекст подошедшего обработчика и `next`, который запускает
обработчик; если не вызвать `next`, обработчик не выполнится. Первое зарегистрированное middleware —
самое внешнее. Middleware выполняется только для событий, под которые подошёл какой-то обработчик.

```kotlin
bot.use { ctx, next ->
    val started = System.nanoTime()
    try {
        next()
    } finally {
        log.info("${ctx.update.updateType} took ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
```

```java
bot.use((ctx, next) -> {
    if (isBanned(ctx.getUser())) return; // обработчик не выполнится
    next.proceed();
});
```

### Роутеры

`Router` — группа обработчиков со своим middleware. Роутеры помогают разбить бота на модули по
функциям и применить middleware только к части обработчиков:

```kotlin
val admin = Router()
    .use { ctx, next -> if (ctx.user?.userId in admins) next() else ctx.reply("Admins only") }
    .onCommand("ban", "Ban a user") { ... }
    .onCommand("stats", "Statistics") { ... }

bot.onCommand("start") { ... }
bot.include(admin)            // проверяется после /start и до общего обработчика
bot.onMessage { ... }
```

Обработчики подключённого роутера участвуют в обычном порядке «выполняется первый подходящий» в том
месте, где вызван `include`. Middleware бота оборачивает и обработчики роутера; middleware роутера
оборачивает только его собственные обработчики и обработчики вложенных в него роутеров.

### Состояние диалога

Для разговоров из нескольких шагов (анкеты, мастера, оформление заказа) у каждого диалога, то есть
пользователя в чате, есть необязательное состояние: имя и строковые значения. Обработчики читают
его как `state` и `stateData` и меняют через `setState`, `updateStateData` и `clearState`;
`Filters.state` маршрутизирует по нему:

```kotlin
bot.onCommand("register") {
    setState("name")
    reply("What is your name?")
}
bot.onMessage(Filters.state("name") and Filters.hasText()) {
    setState("age", mapOf("name" to text!!))
    reply("How old are you?")
}
bot.onMessage(Filters.state("age")) {
    reply("Hi, ${stateData["name"]}! Age: $text")
    clearState()
}
```

```java
bot.onMessage(Filters.state("name"), ctx -> {
    ctx.setStateBlocking("age", Map.of("name", ctx.getText()));
    ctx.replyBlocking("How old are you?");
});
```

Состояния хранятся в `BotConfig.stateStorage`. `InMemoryStateStorage` по умолчанию теряет их при
перезапуске; готовые хранилища в базе данных и Redis описаны в разделе [Хранилища](#хранилища).
Своё хранилище — это реализация `StateStorage` (Kotlin, `suspend`-методы) или наследник
`BlockingStateStorage` (Java, выполняется на `Dispatchers.IO`):

```kotlin
val bot = MaxBot(token, BotConfig(stateStorage = RedisStateStorage(redis)))
```

Бот читает состояние один раз на событие до запуска фильтров, поэтому фильтры видят его без
обращения к хранилищу.

### Сцены и мастера

`Scene` описывает мастер поверх состояния диалога: вопросы задаются по очереди, ответы проверяются,
пользователь может вернуться на шаг назад или отменить. Сцена работает с любым `StateStorage`:
состояние диалога называется `scene:<сцена>:<шаг>`, а ответы лежат в `DialogState.data` под именами
шагов.

```kotlin
val survey = scene("survey") {
    enterOnCommand("survey", "Пройти опрос")       // вход по команде (и пункт меню)
    enterOnCallback("survey")                       // и по кнопке с этим payload
    step("name") { prompt("Как вас зовут?") }
    step("age") {
        prompt { reply("Сколько вам лет, ${answers["name"]}?") }
        validate("Отправьте число от 1 до 120") { it.toIntOrNull() in 1..120 }
    }
    step("rating") {
        prompt {
            reply {
                text("Как вам бот?")
                keyboard {
                    row((1..5).map { option("$it ⭐", "$it") })
                    row(backButton("◀ Назад"), cancelButton("Отмена"))
                }
            }
        }
    }
    onFinish { reply("Спасибо, ${answers["name"]}! Оценка: ${answers["rating"]}") }
    onCancel { reply("Опрос отменён") }
}
bot.include(survey)          // до обработчиков вроде onMessage { }, иначе они заберут ответы
```

```java
Scene survey = Scene.builder("survey")
        .enterOnCommand("survey")
        .step("name", step -> step.prompt("Как вас зовут?"))
        .step("age", step -> step
                .prompt(ctx -> ctx.replyBlocking("Сколько вам лет?"))
                .validate("Отправьте число", answer -> answer.matches("\\d+")))
        .onFinish(ctx -> ctx.replyBlocking("Спасибо, " + ctx.getAnswers().get("name")))
        .build();
bot.include(survey);
```

- Ответ — текст сообщения или значение кнопки `option(text, value)` текущего шага. Нажатия кнопок
  прошлых шагов не принимаются. `input { ctx -> ... }` читает другие ответы, например телефон из
  присланного контакта; `null` считается неверным ответом.
- Неверный ответ получает текст ошибки из `validate` (или реакцию из `onInvalid`, например
  переведённую) и вопрос заново; `repeatPrompt(false)` отключает повтор вопроса.
- `/back` или `backButton` возвращают к предыдущему шагу, `/cancel` или `cancelButton` очищают
  состояние и вызывают `onCancel`. Команды меняются через `backCommand(...)` и `cancelCommand(...)`,
  `null` их отключает. Другие команды сцена не считает ответами, их получают остальные обработчики.
- `survey.enter(ctx, mapOf("order" to "42"))` входит в сцену из любого обработчика с начальными
  данными; `isActive(ctx)`, `currentStep(ctx)` и `filter()` говорят, где диалог.
- В `prompt`, `onFinish`, `onCancel` и `onInvalid` приходит `SceneContext`: это обычный контекст
  обработчика (`reply`, `locale`, `client`, ...) плюс `answers`, `step` и `answer`.
- Нажатия кнопок сцены подтверждаются уведомлением с текстом кнопки.

Пример [QuestionnaireBot](examples/src/main/kotlin/ru/securitytrip/maxapi/examples/QuestionnaireBot.kt)
— анкета на сцене с переводами, а её тест показывает, как проверять сцены через `BotTester`.

### Клавиатуры: страницы и подтверждение

`Paginator` показывает длинный список по страницам: кнопки элементов текущей страницы и ряд
`◀ 2/7 ▶`, payload которого упакован через `CallbackData` вместе с номером страницы и необязательным
аргументом (например, категорией):

```kotlin
val catalog = Paginator("catalog", pageSize = 5, columns = 1)

fun page(number: Int) = message {
    text("Товары")
    attach(catalog.keyboard(products, number) { Buttons.callback(it.name, "product:${it.id}") })
}

bot.onCommand("catalog") { reply(page(0)) }
bot.onCallback(catalog.filter()) { editMessage(page(catalog.page(this))) }
```

`Confirmation` — вопрос «Да / Нет»:

```kotlin
val delete = Confirmation("delete")

bot.onCommand("delete") { reply { text("Удалить заказ 42?"); attach(delete.keyboard("42", "Да", "Нет")) } }
bot.onCallback(delete.yes()) { orders.delete(delete.arg(this)); editMessage(message { text("Удалено") }) }
bot.onCallback(delete.no()) { editMessage(message { text("Оставили") }) }
```

Раскладка учитывает лимиты Max: не больше 7 кнопок в ряду и 3 в ряду с кнопкой-ссылкой или другой
широкой кнопкой, не больше 30 рядов (и тем самым 210 кнопок). `Paginator`, страница которого не
помещается, отклоняется с `IllegalArgumentException` при создании, а страница из широких кнопок,
которая не влезает, — при сборке. `rows(...)` и `buttons(...)` возвращают ряды для своей клавиатуры.

### Переводы

`Translations` выбирает текст по `ctx.locale` пользователя, а для незнакомых языков берёт запасной.
Тексты задаются картой или файлами `ResourceBundle`, без новых зависимостей:

```kotlin
val texts = Translations.of(
    fallback = "ru",
    texts = mapOf(
        "ru" to mapOf("hello" to "Привет, {0}!", "apples.one" to "{0} яблоко", "apples.few" to "{0} яблока", "apples.many" to "{0} яблок"),
        "en" to mapOf("hello" to "Hello, {0}!", "apples.one" to "{0} apple", "apples.other" to "{0} apples"),
    ),
)
bot.onCommand("start") { reply(texts.text(this, "hello", user?.firstName)) }
texts.plural("ru", "apples", 22)            // "22 яблока"
```

```java
// messages_ru.properties и messages_en.properties в classpath, в UTF-8
Translations texts = Translations.fromBundles("messages", "ru", "en");
ctx.replyBlocking(texts.text(ctx, "hello", ctx.getUser().getFirstName()));
```

Текст ищется для полной локали (`pt-BR`), затем для языка (`pt`), затем для запасного языка;
отсутствующий ключ возвращается как есть. `{0}`, `{1}` заменяются аргументами, `{name}` — значениями
карты. `plural` выбирает форму числа по ключам `<ключ>.one`, `.few`, `.many` (русский, украинский,
белорусский) или `.one`, `.other` (остальные языки), а `{0}` в ней — само число.

### Реестр чатов

В Max нет метода, который перечисляет чаты бота (`GET /chats` удалён в июне 2026), поэтому бот сам
запоминает их по событиям. Включите хранилище чатов в `BotConfig.chatStore`:

```kotlin
val bot = MaxBot(token, BotConfig(chatStore = JdbcChatStore(dataSource).createTable()))

val groups = bot.chats(ChatType.CHAT, ChatType.CHANNEL)        // группы и каналы
val dialogs = bot.chats(ChatType.DIALOG)                       // пользователи, запустившие бота
```

```java
List<KnownChat> chats = bot.chatsBlocking();
```

- `bot_added` добавляет группу или канал (название берётся через `getChat`), `bot_started` — диалог
  с пользователем, `bot_removed` и `bot_stopped` помечают чат неактивным, `chat_title_changed`
  обновляет название. Чат, о котором бот ещё не знает, запоминается и по первому сообщению из него,
  так что чаты, где бот был до подключения реестра, тоже попадают в список.
- Эти типы событий запрашиваются у polling и вебхука, даже если обработчиков для них нет.
- Событие может прийти дважды или с опозданием: у записи хранится время события, и более старое
  событие никогда не заменяет более новое. Удалённый чат остаётся неактивной записью, чтобы поздняя
  повторная доставка `bot_added` не вернула его.
- Хранилища: `InMemoryChatStore`, `JdbcChatStore`, `RedisChatStore`; своё — реализация `ChatStore`
  или наследник `BlockingChatStore`. Со стартером его создаёт `max.bot.storage=jdbc|redis`.

### Рассылки

`Broadcaster` отправляет сообщение списку пользователей или чатов в пределах лимитов Max и хранит
прогресс, поэтому прерванная рассылка продолжается после перезапуска:

```kotlin
val broadcaster = Broadcaster(bot.client, JdbcBroadcastStore(dataSource).createTable())
val targets = BroadcastTarget.users(subscriberIds) + bot.chats(ChatType.CHAT).map(BroadcastTarget::of)

val report = broadcaster.send("sale-2026-11", targets) { target -> message { text("Скидки до пятницы!") } }
println("Отправлено ${report.sent}, недоступны ${report.unavailable.size}, ошибки ${report.failed.size}")
```

```java
BroadcastReport report = broadcaster.sendBlocking("sale-2026-11", targets,
        NewMessageBody.builder().text("Скидки до пятницы!").build());
```

- Сообщения уходят не быстрее `requestsPerSecond` (25 по умолчанию: Max разрешает боту 30 запросов в
  секунду, остаток — обработчикам) и не больше `parallelism` одновременно.
- На 429 вся рассылка ждёт `Retry-After` (секунду, если заголовка нет) и повторяет сообщение, всего
  до `maxAttempts` раз.
- `ForbiddenException` (пользователь остановил бота, бота нет в чате) и 404 записываются как
  `UNAVAILABLE` и не повторяются; другие ошибки — как `FAILED`.
- Результат по каждому адресату сразу пишется в `BroadcastStore`. Повторный `send` с тем же id
  пропускает отправленных и недоступных и пробует снова ошибочных. При падении между отправкой и
  записью адресат получит сообщение ещё раз.
- `BroadcastReport` — итоги запуска: `total`, `skipped` (обработаны раньше), `sent`, `unavailable`,
  `failed` с ошибками, `duration` и `isComplete`. Полный прогресс — `store.outcomes(id)`, а
  `store.delete(id)` удаляет его.
- Хранилища: `InMemoryBroadcastStore`, `JdbcBroadcastStore`, `RedisBroadcastStore` (с необязательным
  TTL). Стартер создаёт бин `Broadcaster` с хранилищем из `max.bot.storage`.

### Обработка ошибок

Исключения из обработчиков никогда не останавливают бота. Они передаются в обработчик ошибок вместе
с событием; ошибки polling приходят с `update = null`. По умолчанию ошибки пишутся в
`System.Logger` с именем `ru.securitytrip.maxapi.bot`.

```kotlin
bot.onError { error, update ->
    log.error("Failed to handle ${update?.updateType}", error)
}
```

```java
bot.onError((error, update) -> log.error("Failed to handle update", error));
```

### Long polling

| Метод                | Откуда   | Поведение                                                        |
|----------------------|----------|------------------------------------------------------------------|
| `startPolling()`     | Kotlin   | `suspend`; работает до `stop()` или до отмены корутины           |
| `start()`            | оба      | запускается в фоне и возвращает `Job`                            |
| `startBlocking()`    | Java     | блокирует поток до `stop()` или фатальной ошибки                 |
| `stop()`             | оба      | перестаёт запрашивать события; уже полученные дообрабатываются   |
| `close()`            | оба      | сразу отменяет всё, включая события в очереди                    |

Полученные события сервер уже считает доставленными, поэтому при остановке вызовите `stop()` и
дождитесь окончания polling (`startBlocking()` вернёт управление, `Job` из `start()` завершится) и
только потом `close()`, иначе эти события потеряются.

Как это работает:

- События распределяются между `BotConfig.parallelism` воркерами (по умолчанию 4) **по чатам**:
  события одного чата обрабатываются строго по одному и по порядку, разных чатов — параллельно.
- У каждого воркера ограниченная очередь (`queueCapacity`); когда она заполнена, polling ждёт.
- Сетевые и серверные ошибки передаются в `onError` и повторяются с паузой, которая удваивается от
  1 секунды до `maxErrorBackoff`.
- Неверный токен (`UnauthorizedException`) и активная подписка на вебхук (`MaxException`)
  останавливают polling: `startPolling()` и `startBlocking()` выбрасывают их. Пока бот подписан на
  вебхук, Max отвечает на long polling пустыми страницами, поэтому перед запуском polling проверяет
  подписки, а не получает молча пустоту. `deleteWebhook()` снимает их, например после
  вебхук-сервера, который не отписался при остановке.

```kotlin
val bot = MaxBot(token, BotConfig(parallelism = 16, pollTimeoutSeconds = 60))
```

Long polling ограничен по скорости и по времени хранения событий; API рекомендует вебхуки для
продакшена. Polling и вебхуки нельзя использовать одновременно.

### Вебхуки

1. Подпишитесь один раз (например, при деплое). Запрашиваются только те типы событий, которые бот
   обрабатывает:

   ```kotlin
   bot.setWebhook("https://example.com/max/webhook", secret = "my-secret-123")
   ```

   URL должен быть HTTPS на порту 443. Секрет (5–256 символов из `[A-Za-z0-9_-]`) приходит в
   заголовке `X-Max-Bot-Api-Secret` каждого запроса. Регистрируйте обработчики до вызова:
   `setWebhook` выбрасывает исключение, если их нет, потому что подписка не получила бы полезных
   событий.

2. Передавайте запросы вашего HTTP-сервера в `WebhookHandler`:

   ```kotlin
   val handler = bot.webhookHandler("my-secret-123")
   ```

   **Spring Boot:**

   ```java
   @RestController
   class MaxWebhookController {
       private final WebhookHandler handler;

       MaxWebhookController(MaxBot bot) {
           this.handler = bot.webhookHandler("my-secret-123");
       }

       @PostMapping("/max/webhook")
       ResponseEntity<Void> webhook(@RequestBody String body,
                                    @RequestHeader(value = WebhookHandler.SECRET_HEADER, required = false) String secret) {
           return handler.handleAsync(body, secret)
                   ? ResponseEntity.ok().build()
                   : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
       }
   }
   ```

   **Ktor:**

   ```kotlin
   routing {
       post("/max/webhook") {
           val ok = handler.handle(call.receiveText(), call.request.headers[WebhookHandler.SECRET_HEADER])
           call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.Unauthorized)
       }
   }
   ```

Методы `WebhookHandler`:

| Метод                    | Поведение                                                                   |
|--------------------------|-----------------------------------------------------------------------------|
| `handle` (`suspend`)     | проверяет секрет, разбирает событие и выполняет обработчик до возврата      |
| `handleBlocking`         | то же, блокирующе; для Java и сервлет-контейнеров                           |
| `handleAsync`            | сразу проверяет и разбирает, обработчик выполняется в фоне                  |

Все они возвращают `false`, если секрет не совпал (отвечайте 401), и `true` в остальных случаях
(отвечайте 200). Max повторяет доставку, пока не получит `200 OK`, до 8 часов, поэтому отвечайте 200,
даже если обработчик упал: ошибка уйдёт в `onError`. С `handleAsync` события одного чата могут
обрабатываться не по порядку, а после `bot.close()` метод выбрасывает `IllegalStateException`:
тогда отвечайте ошибкой, чтобы Max доставил событие позже.

Если 8 часов подряд ни одна доставка не удалась (сервер лежал или был недоступен), Max снимает
подписку, и события копятся, пока бот не подпишется снова. `bot.ensureWebhook(url, secret)` проверяет
подписку и, если её нет, подписывается заново: вызывайте его периодически (стартер Spring Boot делает
это сам). Повторная подписка на тот же адрес безвредна. Возобновление пишется в лог и приходит в
`MaxEventListener.onWebhookResubscribe`.

Чтобы вернуться к long polling, снимите подписку через `bot.deleteWebhook()` или
`bot.client.unsubscribe(url)`.

## Клиент API

### Вызов методов

```kotlin
val client = MaxClient(token)

val me = client.getMyInfo()
val chat = client.getChat(chatId)
val sent = client.sendMessageToChat(chatId, "Hello")
client.editMessage(sent.body!!.mid, NewMessageBody(text = "Hello, edited"))
client.pinMessage(chatId, sent.body!!.mid, notify = false)

client.close()
```

Все 33 метода по разделам (`addMembers` Max больше не обслуживает, см. ниже):

| Раздел        | Методы                                                                                                    |
|---------------|-----------------------------------------------------------------------------------------------------------|
| Бот           | `getMyInfo`, `editMyCommands`                                                                             |
| Чаты          | `getChat`, `editChat`, `sendAction`, `getPinnedMessage`, `pinMessage`, `unpinMessage`, `leaveChat`        |
| Участники     | `getMembership`, `getMembers`, ~~`addMembers`~~, `removeMember`, `getAdmins`, `postAdmins`, `deleteAdmins` |
| Сообщения     | `getMessages`, `getMessageById`, `sendMessage`, `editMessage`, `deleteMessage`, `answerOnCallback`, `getVideoAttachmentDetails` |
| Комментарии   | `getComments`, `getCommentById`, `sendComment`, `editComment`, `deleteComment`                            |
| События       | `getUpdates`, `getSubscriptions`, `subscribe`, `unsubscribe`                                              |
| Загрузка      | `getUploadUrl`                                                                                            |

Поверх них есть удобные методы: `sendMessageToChat`, `sendMessageToUser`, `sendLongMessage*`
(см. [Длинный текст](#длинный-текст)), `answerOnCallback(callbackId, notification)`, `upload`,
`uploadImage`, `uploadVideo`, `uploadAudio` и `uploadFile`.

`addMembers` (`POST /chats/{chatId}/members`) помечен `@Deprecated`: Max ограничил этот метод
9 сентября 2026 года и удалил 30 сентября, поэтому сервер его отклоняет. Метод оставлен ради
совместимости (в спецификации он ещё есть), замены нет.

Необязательные параметры — аргументы Kotlin по умолчанию:

```kotlin
client.getMessages(chatId = chatId, count = 10)
client.removeMember(chatId, userId, block = true)
```

### Клиент в Java

```java
MaxClient client = MaxClient.create(token);

// Блокирующий фасад: каждый метод ждёт ответа.
MaxBlockingClient api = client.blocking();
BotInfo me = api.getMyInfo();
api.sendMessageToChat(chatId, "Hello");
List<Message> last = api.getMessages(chatId, null, null, null, 10);

// Асинхронный фасад: каждый метод возвращает CompletableFuture.
client.async().getChat(chatId)
        .thenAccept(chat -> System.out.println(chat.getTitle()))
        .exceptionally(error -> { error.printStackTrace(); return null; });

client.close();
```

У обоих фасадов ровно те же методы, что у `MaxClient`. Необязательные параметры доступны как
перегрузки (`getMembers(chatId)`, `getMembers(chatId, userIds)`, ...). Отмена future отменяет
HTTP-запрос. Не используйте блокирующий фасад в потоках event loop (Netty, Vert.x, главный поток
Android).

### Сборка сообщений

`NewMessageBody` — содержимое отправляемого или изменяемого сообщения. Собрать его можно тремя
способами:

```kotlin
// Kotlin DSL
val body = message {
    markdown("**Order #42** is ready")
    replyTo(originalMid)          // или forward(mid)
    notify(false)                 // доставить без звука
    attach(photo)                 // любой AttachmentRequest
    keyboard { row { callback("Pick up", "pickup:42") } }
}

// Конструктор
val plain = NewMessageBody(text = "Hello", format = TextFormat.HTML)
```

```java
// Билдер для Java
NewMessageBody body = NewMessageBody.builder()
        .html("<b>Order #42</b> is ready")
        .replyTo(originalMid)
        .keyboardRow(Buttons.callback("Pick up", "pickup:42"))
        .build();
```

Вложения, для которых не нужна загрузка:

```kotlin
PhotoAttachmentRequest.ofUrl("https://example.com/cat.png")  // сервер сам скачает картинку
PhotoAttachmentRequest.ofToken(token)                         // повторно отправить картинку, уже загруженную в Max
StickerAttachmentRequest.ofCode(code)
LocationAttachmentRequest(latitude = 55.75, longitude = 37.62)
ShareAttachmentRequest.ofUrl("https://example.com")           // превью ссылки
ContactAttachmentRequest(ContactAttachmentRequestPayload(name = "Support", vcfPhone = "+70000000000"))
```

При изменении сообщения `attachments = null` оставляет текущие вложения, а пустой список удаляет
их все. В билдере пустой список отправляет `clearAttachments()`.

Сообщение без текста, без вложений и без пересылки `sendMessage` отклоняет сразу с
`IllegalArgumentException`: сервер такие сообщения всё равно не принимает.

Так же, без запроса к серверу, отклоняется всё, что нарушает лимиты Max (константы — в `MaxLimits`).
Проверяют `sendMessage`, `editMessage`, `answerOnCallback` (новое содержимое сообщения),
`sendComment`, `editComment`, `editMyCommands` и `MaxBot.syncCommands`:

| Что                                   | Лимит                                                          |
|---------------------------------------|----------------------------------------------------------------|
| текст сообщения или комментария       | до 4000 символов                                               |
| клавиатура                            | до 30 рядов, от 1 до 210 кнопок, до 7 кнопок в ряду            |
| ряд с кнопкой `link`, `open_app`, `request_geo_location` или `request_contact` | до 3 кнопок           |
| текст кнопки                          | 1–128 символов                                                 |
| payload кнопок `callback` и `clipboard` | до 1024 символов                                             |
| `url` кнопки `link`                   | до 2048 символов                                               |
| payload кнопки `open_app`             | до 512 символов, только `A-Z`, `a-z`, `0-9`, `_` и `-`         |
| команды бота                          | до 32; имя 1–64 символа, описание 1–128                        |

Длина считается как `String.length`, в кодовых единицах UTF-16, потому что так её считает сервер:
эмодзи 😀 занимает 2 символа из 4000, флаг — 4, разметка Markdown и HTML тоже считается.

### Длинный текст

В одно сообщение помещается 4000 символов. `sendLongMessage` (и `sendLongMessageToChat`,
`sendLongMessageToUser`, `replyLong` в обработчиках) отправляет текст длиннее несколькими
сообщениями, а короткий — одним, как `sendMessage`:

```kotlin
client.sendLongMessageToChat(chatId, message {
    markdown(report)
    keyboard { row { callback("OK", "ok") } }
})

bot.onCommand("report") { replyLong(message { markdown(report) }) }
```

```java
client.blocking().sendLongMessageToChat(chatId, NewMessageBody.builder().markdown(report).build());
bot.onCommand("report", ctx -> ctx.replyLongBlocking(report));
```

- Текст режется между абзацами, иначе между строками, иначе между словами; более мелкая граница
  выбирается, только если крупная оставила бы часть заполненной меньше чем наполовину. Слово
  длиннее сообщения режется там, где придётся, но не посреди суррогатной пары (эмодзи), не перед
  диакритикой и не рядом с соединителем нулевой ширины.
- Разметка остаётся корректной в каждой части. В Markdown ссылки и экранирование вроде `\*` не
  разрываются, а `**жирный**`, `` `код` `` и другие участки, через которые проходит разрез, закрываются
  в конце части и открываются снова в начале следующей; цитата `> ` и заголовок `# ` продолжаются в
  следующей части. В HTML не разрываются теги и сущности, а открытые теги закрываются и открываются
  снова вместе с атрибутами.
- Клавиатура и другие вложения уходят в последнее сообщение, ответ или пересылка (`link`) — в
  первое; `notify` и формат у всех частей одинаковые. Если отправка части не удалась, уже
  отправленные части остаются.

`TextSplitter.split(text, format)` возвращает части, не отправляя их, например чтобы отправить
текст в комментариях.

### Форматирование

Укажите формат `MARKDOWN` (`markdown(...)` в билдере) или `HTML` (`html(...)`):

| Стиль          | Markdown                  | HTML                         |
|----------------|---------------------------|------------------------------|
| Жирный         | `**text**` или `__text__` | `<b>`, `<strong>`            |
| Курсив         | `*text*` или `_text_`     | `<i>`, `<em>`                |
| Зачёркнутый    | `~~text~~`                | `<s>`, `<del>`               |
| Подчёркнутый   | `++text++`                | `<u>`, `<ins>`               |
| Моноширинный   | `` `code` ``              | `<code>`, `<pre>`            |
| Выделение      | `^^text^^`                | `<mark>`                     |
| Ссылка         | `[text](https://...)`     | `<a href="https://...">`     |
| Упоминание     | `[Name](max://user/123)`  | —                            |
| Цитата         | `> text`                  | `<blockquote>`               |
| Заголовок      | `# text`                  | `<h1>`                       |

В полученных сообщениях форматирование приходит как `MessageBody.markup` — список диапазонов
`MarkupElement`.

### Загрузка файлов

Методы `upload*` выполняют всю трёхшаговую загрузку (получить URL, загрузить файл, собрать
вложение) и возвращают готовый к отправке `AttachmentRequest`:

```kotlin
val photo = client.uploadImage(UploadSource.of(File("cat.png")))
val video = client.uploadVideo(UploadSource.of(Path.of("movie.mp4")))
val file = client.uploadFile(UploadSource.of(bytes, "report.pdf"))
val audio = client.uploadAudio(UploadSource.of(inputStream, "voice.mp3"))

client.sendMessageToChat(chatId, message { text("Here is the report"); attach(file) })
```

`UploadSource` принимает `File`, `Path`, `ByteArray` или `InputStream` (читается один раз). Тип
содержимого определяется по имени файла.

Ограничения одной загрузки:

| Тип     | Форматы                               | Ограничение                    |
|---------|---------------------------------------|--------------------------------|
| `IMAGE` | JPG, JPEG, PNG, GIF, TIFF, BMP, HEIC  | 50 МБ и 7680×7680 пикселей     |
| `VIDEO` | MP4, MOV, MKV, WEBM                   | 250 МБ                         |
| `AUDIO` | MP3, WAV, M4A и другие                | 256 МБ и 60 минут              |
| `FILE`  | любые распространённые форматы        | 4 ГБ                           |

Аудио, файл, стикер и контакт должны быть единственным вложением сообщения.

Крупные медиафайлы могут ещё обрабатываться на сервере в момент отправки сообщения. Тогда сервер
отвечает `attachment.not.ready`, и `sendMessage`, `editMessage` и `answerOnCallback` повторяют
запрос автоматически (по умолчанию 5 раз с интервалом 1 секунда; см. [Настройка](#настройка)).

URL загрузки принадлежит другому хосту, поэтому библиотека никогда не отправляет туда токен бота.

### Постраничная выдача

Методы, возвращающие страницы, отдают `marker`, который нужно передать в следующий вызов:

```kotlin
var marker: Long? = null
do {
    val page = client.getMembers(chatId, marker = marker, count = 100)
    page.members.forEach { println("${it.firstName} admin=${it.isAdmin}") }
    marker = page.marker
} while (marker != null)
```

`getMessages` и `getComments` листаются по времени: передавайте `before` / `after` (миллисекунды
Unix).

## Spring Boot

`maxapi-spring-boot-starter` создаёт `MaxClient` и `MaxBot` из свойств `max.bot.*` и запускает и
останавливает бота вместе с приложением. Собирается и тестируется на Spring Boot 4.1 (Java 17+).

```kotlin
dependencies {
    implementation("io.github.securitytrip:maxapi-spring-boot-starter:<version>")
    implementation("org.springframework.boot:spring-boot-starter-web") // режим webhook: Spring MVC или WebFlux
}
```

```yaml
max:
  bot:
    token: ${MAX_BOT_TOKEN}
    mode: polling            # polling (по умолчанию), webhook или none
    sync-commands: true      # показать команды с описаниями в меню бота при запуске
    trust-russian-root-ca: true  # по умолчанию; см. «TLS-сертификат» ниже
    rate-limit: max          # none (по умолчанию) или max: лимиты частоты запросов Max
    storage: memory          # memory (по умолчанию), jdbc или redis; см. «Хранилища»
    webhook:
      path: /max/webhook     # endpoint в вашем сервере Spring MVC или WebFlux
      url: https://bot.example.com/max/webhook   # подписаться при запуске (необязательно)
      secret: ${MAX_WEBHOOK_SECRET}
      async: false           # true: отвечать, не дожидаясь обработчика
      check-interval: 10m    # как часто проверять подписку на url; 0 отключает проверку
```

Обработчики берутся из бинов: каждый `MaxBotConfigurer` вызывается с ботом, каждый бин `Router`
подключается, и то и другое — в порядке `@Order`.

```kotlin
@Configuration
class BotHandlers {
    @Bean
    fun start() = MaxBotConfigurer { bot -> bot.onCommand("start", "Start") { reply("Hi!") } }

    @Bean
    fun orders(service: OrderService) = Router()
        .onCommand("orders", "My orders") { reply(service.ordersOf(user!!.userId)) }
}
```

```java
@Bean
MaxBotConfigurer handlers() {
    return bot -> bot.onCommand("start", "Start", ctx -> ctx.replyBlocking("Hi!"));
}
```

| Режим     | Что происходит                                                                            |
|-----------|-------------------------------------------------------------------------------------------|
| `polling` | long polling запускается вместе с контекстом; при остановке бот прекращает polling и дообрабатывает уже полученные события |
| `webhook` | `POST max.bot.webhook.path` принимает доставки (сервлетные и реактивные веб-приложения); если задан `webhook.url`, бот подписывается при запуске и каждые `webhook.check-interval` проверяет, что подписка на месте, а если Max её снял, подписывается заново |
| `none`    | ничего не запускается; доставляйте события сами                                           |

`max.bot.rate-limit=max` включает `RateLimit.MAX` (см. [Надёжность](#надёжность-и-мониторинг)); по
умолчанию ограничения нет, как и в предыдущих версиях. Бины `MaxEventListener` (и `MaxMetrics`, если
подключён `maxapi-micrometer` и есть `MeterRegistry`, и `MaxTracing`, если подключён
`maxapi-opentelemetry` и есть бин `OpenTelemetry`) получают события клиента и бота. Бины `StateStorage`, `MarkerStore`,
`UpdateDeduplicator` (или `SuspendUpdateDeduplicator`) и `ChatStore` используются ботом, а бин `Broadcaster` отправляет рассылки с
прогрессом в бине `BroadcastStore`, см. [Хранилища](#хранилища).

Endpoint вебхука отвечает 200 на принятые события, 401 на неверный секрет, 400 на тело, которое не
является событием, и 503, когда бот закрыт. В приложении на Spring WebFlux endpoint — реактивная
`RouterFunction` с теми же ответами: обработчик выполняется на `Dispatchers.Default`, а не в
event loop, и ответ ждёт его, не блокируя поток (если не включён `webhook.async`). Чтобы заменить бин по умолчанию, объявите свой
`MaxClient`, `MaxBot` или `BotConfig`; бины `MaxClientConfigCustomizer` меняют настройки клиента
(политику повторов, `OkHttpClient`).

Аргумент `@MaxWebAppUser` в контроллерах бэкенда мини-приложения настраивается свойствами
`max.bot.web-app.*`, см. [Бэкенд на Spring MVC](#бэкенд-на-spring-mvc).

Полное приложение — в [examples-spring](examples-spring).

### Health-индикатор

Если в classpath есть Spring Boot Actuator, стартер регистрирует health-контрибьютор `max`
(`/actuator/health/max`):

| Часть     | `DOWN`, когда                                                                             |
|-----------|-------------------------------------------------------------------------------------------|
| `bot`     | polling завершился (ошибка, которая его остановила, — в `pollingError`); в режиме webhook нет endpoint (приложение не веб) или пропала подписка на `webhook.url`; API не отвечает или отвергает токен |
| `storage` | при `max.bot.storage=jdbc` или `redis`: не отвечает база данных или Redis                 |

Часть `bot` обращается к API (`getSubscriptions` в режиме webhook, иначе `getMyInfo`) не чаще раза в
`max.bot.health.cache-ttl`, так что частые проверки не расходуют лимит в 30 запросов в секунду:

```yaml
max:
  bot:
    health:
      cache-ttl: 30s     # по умолчанию; 0 — обращаться при каждой проверке
      timeout: 5s        # сколько проверка ждёт API и хранилище
management:
  endpoint:
    health:
      show-details: always
  health:
    max:
      enabled: true      # false отключает контрибьютор
```

Контрибьютор не входит в группы `liveness` и `readiness`: добавьте его в группу сами
(`management.endpoint.health.group.readiness.include=readinessState,max`), если недоступный API
должен выводить экземпляр из балансировки.

### Нативный образ

В jar `maxapi` лежат метаданные рефлексии для GraalVM в `META-INF/native-image`: все классы моделей
(их создаёт Jackson, включая подклассы `Update` и других полиморфных иерархий), корневой сертификат
Russian Trusted Root CA и встроенные описания Kotlin. Стартер добавляет те же подсказки в Spring AOT
(`MaxRuntimeHints` из `META-INF/spring/aot.factories`). Сборка проверяет их: приложение со стартером
проходит AOT-обработку, сгенерированный код компилируется, и бот запускается из него.

Нативный образ бота на Spring Boot собирается как обычно, плагинами Spring Boot и GraalVM Native
Build Tools на GraalVM JDK 17 или новее:

```kotlin
plugins {
    id("org.springframework.boot") version "4.1.1"
    id("org.graalvm.buildtools.native") version "1.1.8"
}
```

```bash
./gradlew nativeCompile        # build/native/nativeCompile/<приложение>
./gradlew nativeTest           # тесты приложения, скомпилированные в нативный код
```

Полная нативная сборка занимает несколько минут и несколько гигабайт памяти, поэтому CI библиотеки не
запускает её на каждое изменение.

## Ktor

`maxapi-ktor` добавляет в маршрутизацию Ktor функцию `maxWebhook`:

```kotlin
val bot = MaxBot(token)
bot.onCommand("start") { reply("Hello from Ktor!") }
bot.setWebhook("https://bot.example.com/max/webhook", secret)

embeddedServer(Netty, port = 8080) {
    routing { maxWebhook("/max/webhook", bot, secret) }
    monitor.subscribe(ApplicationStopping) { runBlocking { bot.client.unsubscribe("https://bot.example.com/max/webhook") } }
}.start(wait = true)
```

Ответы такие же, как в стартере Spring. `async = true` отвечает, не дожидаясь окончания
обработчика. Снимайте подписку при остановке сервера, как в примере: пока она есть, long polling
не получает событий. См. [examples-ktor](examples-ktor). Плагин `MaxWebApp` для бэкенда
мини-приложения описан в разделе [Бэкенд на Ktor](#бэкенд-на-ktor).

## Мини-приложения

Мини-приложение — это веб-страница, которую Max открывает внутри чата с ботом. Страница получает
стартовые данные в `window.WebApp.initData` и передаёт их своему бэкенду; бэкенд проверяет подпись и
только после этого доверяет пользователю. `initDataUnsafe` на стороне страницы не подписан, и
полагаться на него нельзя. Всё нужное для бэкенда лежит в пакете `ru.securitytrip.maxapi.webapp`
модуля `maxapi`, новых зависимостей не требуется.

У Max три разные подписи, и путать их нельзя:

| Что проверяется                         | Ключ HMAC-SHA256                      | Подписанные данные                                                 | Метод                      |
|-----------------------------------------|---------------------------------------|--------------------------------------------------------------------|----------------------------|
| `initData` мини-приложения              | `HMAC_SHA256("WebAppData", botToken)` | поля без `hash`, отсортированные по ключу, `key=value` через `\n` | `WebAppData.validate`      |
| телефон из `requestContact()` в Bridge  | `botToken`                            | `authDate=…\nphone=<без +>\nuserId=…`                            | `WebAppPhone.verify`       |
| вложение `contact` от `request_contact` | `botToken`                            | `vcf_info` ровно в том виде, в каком пришёл (с CRLF)               | `ContactAttachment.verify` |

Хеши сравниваются за постоянное время.

### Проверка initData

```kotlin
val data = WebAppData.validate(initData, botToken)                 // maxAge по умолчанию — 1 час
val user = data.user!!                                              // userId, firstName, username, languageCode, photoUrl
println("${user.firstName} открыл приложение из чата ${data.chat?.id}, start_param=${data.startParam}")
```

```java
try {
    WebAppData data = WebAppData.validate(initData, botToken, Duration.ofMinutes(30));
} catch (WebAppDataException e) {
    // e.getReason(): MALFORMED, MISSING_HASH, DUPLICATE_HASH, INVALID_SIGNATURE или EXPIRED
}
```

`validate` проверяет данные [по документации Max](https://dev.max.ru/docs/webapps/validation):
разбирает строку, URL-декодируя значения один раз, требует ровно один `hash`, сверяет подпись и
отклоняет данные, у которых `auth_date` старше `maxAge`. В результате есть пользователь, чат
(`id` и `ChatType`), `queryId`, `authDate`, `startParam`, `ip` и `fields` — все поля, в том числе
те, для которых у класса нет свойства. Исключение `WebAppDataException` (непроверяемое, наследник
`MaxException`) называет причину и никогда не содержит токен.

`WebAppData.verifySignature(initData, botToken)` только проверяет подпись: не разбирает поля и не
смотрит на `auth_date`, а вместо исключения возвращает `false`. Для авторизации используйте
`validate`: старые данные могли быть перехвачены.

### Телефон из requestContact

`window.WebApp.requestContact()` возвращает `{ phone, authDate, hash }`. Страница отправляет этот
объект бэкенду вместе с `initData`, а бэкенд проверяет его для пользователя из `initData`:

```kotlin
val user = WebAppData.validate(initData, botToken).user!!
val phone = WebAppPhone(body.phone, body.authDate, body.hash)
if (!phone.verify(user.userId, botToken)) throw Unauthorized()
```

Ведущий `+` в номере не мешает: перед проверкой он отбрасывается, как требует формула. Возраст
`authDate` метод не проверяет.

### Контакт от кнопки request_contact

Когда пользователь нажимает кнопку `request_contact`, бот получает сообщение с вложением
`ContactAttachment`, и `verify` проверяет, что Max подписал этот контакт для вашего бота:

```kotlin
bot.onMessage({ it.message.body?.attachments.orEmpty().any { a -> a is ContactAttachment } }) {
    val contact = message.body!!.attachments!!.filterIsInstance<ContactAttachment>().first()
    reply(if (contact.verify(token)) "Номер подтверждён" else "Контакт не подтверждён")
}
```

Подпись есть только у контакта, которым пользователь поделился через кнопку; пересланная или
набранная вручную визитка проверку не проходит, поэтому чужой номер за свой не выдать.

### Диплинки

```kotlin
MaxLinks.bot("shop_bot")                          // https://max.ru/shop_bot
MaxLinks.botStart("shop_bot", "ref-42")           // https://max.ru/shop_bot?start=ref-42
MaxLinks.miniApp("shop_bot")                      // https://max.ru/shop_bot?startapp
MaxLinks.miniApp("shop_bot", "promo_summer")      // https://max.ru/shop_bot?startapp=promo_summer
MaxLinks.share("Смотри: https://example.com")     // https://max.ru/:share?text=...
```

Max молча отбрасывает параметр, который нарушает его ограничения, поэтому построители в таком случае
выбрасывают `IllegalArgumentException`: `start` — от 1 до 128 символов (всё, кроме букв, цифр и
`_-.~`, кодируется), `startapp` — до 512 символов только из `A-Za-z0-9_-`. Те же правила для payload
кнопки `open_app` проверяют `MaxLimits.isValidOpenAppPayload` и `MaxLimits.checkOpenAppPayload`, а
`Buttons.openApp` проверяет payload сразу при создании кнопки. Значение `startapp` мини-приложение
получает в `WebAppData.startParam`.

### Бэкенд на Spring MVC

Если задан `max.bot.token`, стартер добавляет в Spring MVC аргумент `@MaxWebAppUser`. Параметр
может иметь тип `WebAppUser` или `WebAppData`; без `initData` или с неверными данными запрос получает
401 ещё до вызова метода.

```kotlin
@RestController
class MiniAppController {
    @GetMapping("/api/me")
    fun me(@MaxWebAppUser user: WebAppUser) = mapOf("id" to user.userId, "name" to user.firstName)

    @PostMapping("/api/order")
    fun order(@MaxWebAppUser data: WebAppData, @RequestBody order: Order) = orders.create(data.user!!.userId, order)
}
```

```yaml
max:
  bot:
    web-app:
      source: header           # header (по умолчанию), parameter (query или форма) или body
      header: X-Max-Init-Data  # заголовок для source=header
      parameter: initData      # параметр для source=parameter
      max-age: 1h              # насколько старым может быть auth_date
```

На странице: `fetch('/api/me', { headers: { 'X-Max-Init-Data': window.WebApp.initData } })`.
`@MaxWebAppUser(required = false)` передаёт `null`, если `initData` в запросе нет. При
`source: body` всё тело запроса — это `initData`, и прочитать тело ещё раз контроллер не сможет.
Пример бэкенда со страницей мини-приложения — в [examples-spring](examples-spring)
(`MiniAppBackend.kt` и `static/index.html`).

### Бэкенд на Ktor

```kotlin
routing {
    route("/api") {
        install(MaxWebApp) {
            botToken = System.getenv("MAX_BOT_TOKEN")
            maxAge = Duration.ofMinutes(30)       // по умолчанию 1 час
            // header("X-Init") или initData { it.request.queryParameters["initData"] }
        }
        get("/me") { call.respondText("Привет, ${call.maxWebAppUser().firstName}") }
        get("/start") { call.respondText(call.maxInitData().startParam.orEmpty()) }
    }
}
```

Плагин проверяет `initData` из заголовка `X-Max-Init-Data` при каждом вызове маршрутов, в которые
он установлен, и отвечает 401, если данных нет, они неверны или устарели либо в них нет пользователя
(`requireUser = false` пропускает данные без пользователя).

## Тестирование ботов

`maxapi-test` запускает бота без сети: `BotTester` подаёт ему события и записывает вызовы API,
которые делают обработчики.

```kotlin
@Test
fun `start shows the menu`(): Unit = runBlocking {
    BotTester().use { tester ->
        registerHandlers(tester.bot)                 // ваш код, который настраивает бота

        tester.sendMessage("/start")
        assertEquals("Pick an option:", tester.sentMessages.single().text)

        tester.pressButton("like")
        assertEquals("Thanks!", tester.callbackAnswers.single().answer.notification)
    }
}
```

```java
try (BotTester tester = new BotTester()) {
    registerHandlers(tester.getBot());
    tester.sendMessageBlocking("/start");
    assertEquals("Pick an option:", tester.getSentMessages().get(0).getText());
}
```

- `sendMessage`, `pressButton`, `startBot` и `send(update)` запускают обработчик и возвращают
  управление после его завершения; исключение из обработчика пробрасывается, и тест падает.
- `sentMessages`, `callbackAnswers` и `calls` (каждый запрос с методом, путём, параметрами и телом)
  показывают, что сделал бот.
- На вызовы приходят ответы по умолчанию: `sendMessage` возвращает сообщение, `getMyInfo` — бота
  `test_bot`, остальные методы — `{"success":true}`. `stub("GET", "/chats/42", json)` задаёт ответ
  для вызовов, результат которых читает обработчик, а со статусом вроде 404 — для проверки обработки
  ошибок.
- `TestUpdates` собирает другие события: `TestUpdates.message("hi", chatType = ChatType.CHAT)`,
  а для реестра чатов — `botAdded`, `botRemoved`, `botStopped` и `chatTitleChanged`.
- Сцены, клавиатуры и рассылки проверяются так же: `tester.pressButton(payload)` нажимает кнопку
  сцены или страницы, а `Broadcaster(tester.client)` отправляет рассылку в записываемый клиент.

## Модели

Модели разложены по пакетам внутри `ru.securitytrip.maxapi.model`:

| Пакет                       | Содержимое                                                                       |
|-----------------------------|----------------------------------------------------------------------------------|
| `model`                     | общие типы: `ApiEnum`, `SimpleQueryResult`, `ErrorResponse`                      |
| `model.user`                | `User`, `UserWithPhoto`, `BotInfo`, `BotCommand`                                 |
| `model.chat`                | `Chat`, `ChatPatch`, `ChatMember`, `ChatAdmin`, `ChatAdminPermission`, `SenderAction` |
| `model.message`             | `Message`, `MessageBody`, `NewMessageBody`, `Callback`, `CallbackAnswer`, комментарии |
| `model.attachment`          | полученные `Attachment`, отправляемые `AttachmentRequest`, `UploadType`          |
| `model.keyboard`            | `Keyboard` и `Button`                                                            |
| `model.markup`              | `TextFormat` и полученные `MarkupElement`                                        |
| `model.update`              | `Update` и его подклассы, `UpdateType`, `UpdateList`                             |
| `model.subscription`        | подписки на вебхуки `Subscription`                                               |

В каждом файле — один основной класс вместе с небольшими классами его payload; например,
`PhotoAttachment.kt` содержит `PhotoAttachment` и `PhotoAttachmentPayload`.

Модели — неизменяемые data-классы Kotlin, поэтому Java видит геттеры
(`message.getBody().getText()`), а `equals`, `hashCode`, `toString` и `copy` работают как ожидается.

Полиморфные семейства — запечатанные классы:

| Семейство           | Дискриминатор  | Примеры                                                     | Запасной вариант    |
|---------------------|----------------|-------------------------------------------------------------|---------------------|
| `Update`            | `update_type`  | `MessageCreatedUpdate`, `MessageCallbackUpdate`, `BotStartedUpdate` | `UnknownUpdate` |
| `Attachment`        | `type`         | `PhotoAttachment`, `FileAttachment`, `InlineKeyboardAttachment` | `UnknownAttachment` |
| `AttachmentRequest` | `type`         | `PhotoAttachmentRequest`, `VideoAttachmentRequest`          | —                   |
| `Button`            | `type`         | `CallbackButton`, `LinkButton`, `OpenAppButton`             | `UnknownButton`     |
| `MarkupElement`     | `type`         | `StrongMarkup`, `LinkMarkup`, `UserMentionMarkup`           | `UnknownMarkup`     |

```kotlin
for (attachment in message.body?.attachments.orEmpty()) {
    when (attachment) {
        is PhotoAttachment -> println("Photo: ${attachment.payload.url}")
        is FileAttachment -> println("File: ${attachment.filename}, ${attachment.size} bytes")
        is LocationAttachment -> println("At ${attachment.latitude}, ${attachment.longitude}")
        is UnknownAttachment -> println("Something new: ${attachment.type} ${attachment.properties}")
        else -> Unit
    }
}
```

Любой `Update` предоставляет `chatId` и `user` независимо от типа.

Идентификаторы: пользователи и чаты — `Long` (у групповых чатов и каналов они отрицательные),
сообщения и комментарии — `String` (`MessageBody.mid`). Время — миллисекунды Unix в UTC.

Чтобы самостоятельно разбирать или сериализовать модели, используйте `MaxJson.createMapper()`
(snake_case, поля `null` пропускаются) и `MaxJson.parseUpdate(json)`.

## Ошибки и повторы

Все исключения непроверяемые и наследуют `MaxException`:

| Исключение                   | Когда                                                                 |
|------------------------------|-----------------------------------------------------------------------|
| `UnauthorizedException`      | 401: токена нет или он неверный                                       |
| `ForbiddenException`         | 403: нет доступа, например пользователь остановил бота                |
| `NotFoundException`          | 404: чата или сообщения нет или к нему нет доступа                    |
| `TooManyRequestsException`   | 429: лимит запросов превышен после всех повторов; есть `retryAfter`   |
| `MaxApiException`            | любая другая HTTP-ошибка; базовый класс четырёх выше                  |
| `MaxNetworkException`        | сервер недоступен; причина — `IOException`                            |
| `MaxException`               | ответ не удалось разобрать или другой сбой на уровне библиотеки       |

`MaxApiException` содержит `httpStatus`, `errorCode` (например, `chat.denied`), `errorMessage` и
исходное тело `responseBody`:

```kotlin
try {
    client.sendMessageToUser(userId, "Reminder")
} catch (e: ForbiddenException) {
    markUserInactive(userId) // пользователь остановил бота
} catch (e: MaxApiException) {
    log.warn("Max API error ${e.httpStatus} ${e.errorCode}: ${e.errorMessage}")
}
```

Повторы (`RetryPolicy`; по умолчанию 3 повтора, начиная с 500 мс, с удвоением):

- HTTP 429 и 503 означают, что запрос отклонён, поэтому они повторяются для любого запроса.
- HTTP 502, 504 и сетевые ошибки повторяются только для `GET`, `PUT` и `DELETE`. `POST` и `PATCH`
  в этом случае не повторяются: сервер мог уже их выполнить (например, отправить сообщение).
- Учитывается `Retry-After` (секунды или HTTP-дата). Если он больше `maxDelay`, запрос сразу
  завершается `TooManyRequestsException`, а не повторяется слишком рано.
- Остальные ошибки выбрасываются сразу.

## Настройка

```kotlin
val client = MaxClient(
    MaxClientConfig(
        token = token,
        retryPolicy = RetryPolicy(maxRetries = 5, initialDelay = Duration.ofSeconds(1)),
        attachmentReadyRetries = 10,
        attachmentReadyDelay = Duration.ofSeconds(2),
        httpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("proxy.local", 3128)))
            .addInterceptor(HttpLoggingInterceptor()) // com.squareup.okhttp3:logging-interceptor
            .build(),
    ),
)
```

```java
MaxClient client = MaxClient.create(MaxClientConfig.builder(token)
        .retryPolicy(new RetryPolicy(5))
        .attachmentReadyRetries(10)
        .build());
```

| Параметр                  | По умолчанию                    | Описание                                                     |
|---------------------------|---------------------------------|--------------------------------------------------------------|
| `token`                   | обязателен                      | токен доступа бота                                           |
| `baseUrl`                 | `https://platform-api2.max.ru`  | адрес API; меняйте только для тестов или прокси              |
| `httpClient`              | создаётся библиотекой           | свой `OkHttpClient` (прокси, логирование, таймауты)          |
| `retryPolicy`             | `RetryPolicy.DEFAULT`           | см. [Ошибки и повторы](#ошибки-и-повторы); `RetryPolicy.NONE` отключает повторы |
| `attachmentReadyRetries`  | `5`                             | повторы, пока сервер отвечает `attachment.not.ready`         |
| `attachmentReadyDelay`    | 1 секунда                       | пауза между этими повторами                                  |
| `userAgent`               | `maxapi-jvm`                    | заголовок `User-Agent`                                       |
| `rateLimit`               | нет                             | ограничение частоты запросов на клиенте, например `RateLimit.MAX`, см. [Надёжность](#надёжность-и-мониторинг) |
| `eventListener`           | нет                             | получает событие на каждую HTTP-попытку (метрики, трассировка) |
| `trustRussianRootCa`      | `true`                          | HTTP-клиент библиотеки доверяет Russian Trusted Root CA, см. [TLS-сертификат](#tls-сертификат) |

HTTP-клиент по умолчанию использует таймауты 15 с на подключение и 60 с на чтение и запись.
`getUpdates` автоматически получает более длинный таймаут чтения, так что long polling работает и
с вашим клиентом. Переданный клиент библиотека никогда не закрывает.

### TLS-сертификат

Сертификат `platform-api2.max.ru` выпущен в цепочке Russian Trusted Root CA Минцифры. Этого
корневого сертификата нет в JDK, поэтому обычный `OkHttpClient` падает с
`PKIX path building failed`. Библиотека содержит корневой сертификат, и HTTP-клиент, который она
создаёт, доверяет ему в дополнение к сертификатам, которым доверяет JVM. Это касается только этого
клиента: доверие остальной JVM не меняется, и не нужны ни `-Djavax.net.ssl.trustStore`, ни импорт
сертификата в `cacerts`.

Клиент, переданный в `httpClient`, используется как есть. Добавьте ему сертификат через `MaxTls`:

```kotlin
val http = MaxTls.trustRussianRootCa(OkHttpClient.Builder())
    .proxy(proxy)
    .build()
val client = MaxClient(MaxClientConfig(token, httpClient = http))
```

`MaxTls.sslContext` и `MaxTls.trustManager` делают то же для других HTTP-клиентов, а
`MaxTls.russianTrustedRootCa` — сам сертификат. Если JVM уже доверяет этому центру сертификации,
отключите встроенный сертификат: `trustRussianRootCa = false` (`max.bot.trust-russian-root-ca=false`
в Spring Boot).

`BotConfig`:

| Параметр              | По умолчанию | Описание                                                      |
|-----------------------|--------------|---------------------------------------------------------------|
| `parallelism`         | `4`          | число воркеров; один чат всегда обрабатывается по порядку     |
| `queueCapacity`       | `64`         | размер очереди воркера; когда она полна, polling ждёт         |
| `pollTimeoutSeconds`  | `30`         | сколько ждёт один запрос `getUpdates`, 0–90                   |
| `pollLimit`           | `100`        | максимум событий за запрос, 1–1000                            |
| `minPollInterval`     | 300 мс       | минимальный интервал между запросами (API требует ≥ 300 мс)   |
| `maxErrorBackoff`     | 30 с         | максимальная пауза после ошибок polling                       |
| `stateStorage`        | в памяти     | где хранятся [состояния диалогов](#состояние-диалога)          |
| `handlerTimeout`      | нет          | максимальное время работы обработчика                         |
| `deduplicator`        | нет          | отбрасывает повторные доставки события                        |
| `markerStore`         | нет          | сохраняет marker polling между перезапусками                  |
| `eventListener`       | нет          | получает событие на каждое обработанное событие               |

Логи пишутся в `System.Logger` с именами `ru.securitytrip.maxapi.client` (повторы, уровень `DEBUG`)
и `ru.securitytrip.maxapi.bot` (ошибки обработчиков, уровень `ERROR`).

## Надёжность и мониторинг

Всё перечисленное ниже по умолчанию выключено.

**Ограничение частоты.** `RateLimit` заставляет клиент ждать, а не получать ответы 429: token
bucket для всех запросов и, при желании, отдельный для каждого чата и для ответов на нажатия кнопок
в каждом чате. `RateLimit.MAX` соблюдает лимиты Max: 30 запросов в секунду и не больше 2 ответов на
нажатия (`POST /answers`) в секунду в одном чате; запросы идут равномерно, без пачек.

```kotlin
MaxClientConfig(token, rateLimit = RateLimit.MAX)
MaxClientConfig(token, rateLimit = RateLimit(requestsPerSecond = 25.0, perChatRequestsPerSecond = 1.0))
MaxClientConfig(token, rateLimit = RateLimit(20.0).withPerChatAnswers(1.0))
```

В `POST /answers` нет `chat_id`, только `callback_id`, поэтому чат нажатия клиент узнаёт из события
`message_callback`, которое обрабатывает `MaxBot`: ответы из его обработчиков (`answer`,
`editMessage` или `client.answerOnCallback`) ограничиваются по чату, а ответы на нажатия, которые бот
не видел или чьё сообщение удалено, — только общим лимитом. Лимит действует в пределах одного
клиента: у нескольких экземпляров бота с одним токеном бюджеты свои.

**Marker polling.** С `MarkerStore` бот после перезапуска продолжает polling с того места, где
остановился. Marker сохраняется только после того, как обработаны все события, полученные до него,
поэтому при сбое ничего не теряется, но событие может обработаться дважды (доставка «как минимум
один раз»). `FileMarkerStore(path)` хранит его в файле; хранилища в базе данных и Redis описаны в
разделе [Хранилища](#хранилища), а своё — это реализация `MarkerStore` (или наследник
`BlockingMarkerStore` в Java).

**Дубликаты.** Max повторяет доставку вебхука, пока не получит 200, до 8 часов.
`InMemoryUpdateDeduplicator` отбрасывает недавно виденные события по id сообщения, id нажатия или,
для остальных событий, по типу, времени, чату и пользователю (`UpdateDeduplicator.keyOf`). Если
экземпляров бота несколько, используйте общие хранилища из раздела [Хранилища](#хранилища).
`UpdateDeduplicator.isNew` блокирует поток, который обрабатывает событие; хранилище с асинхронным
клиентом реализует вместо него `SuspendUpdateDeduplicator.markSeen` и задаётся через
`BotConfig(suspendDeduplicator = ...)`. Класс может реализовать оба интерфейса, как дедупликаторы
Redis и JDBC: тогда бот вызывает неблокирующий метод.

**Таймаут обработчика.** `handlerTimeout` отменяет слишком долгий обработчик (обработчики на Java
прерываются) и передаёт `HandlerTimeoutException` в `onError`, чтобы зависший обработчик не
блокировал свой чат.

```kotlin
val bot = MaxBot(
    client,
    BotConfig(
        markerStore = FileMarkerStore(Path.of("data/marker")),
        deduplicator = InMemoryUpdateDeduplicator(),
        handlerTimeout = Duration.ofSeconds(30),
    ),
)
```

**Метрики.** `MaxEventListener` получает событие на каждую HTTP-попытку (`RequestEvent`: метод,
шаблон пути вида `/chats/{id}`, статус, длительность, номер попытки) и на каждое обработанное
событие (`UpdateEvent`: событие, обработано ли, длительность, ошибка). `maxapi-micrometer` превращает
их в таймеры `max.client.requests` и `max.bot.updates`, а возобновлённые подписки на вебхук
(`WebhookResubscribeEvent`) — в счётчик `max.bot.webhook.resubscriptions`:

```kotlin
val metrics = MaxMetrics(meterRegistry)
val client = MaxClient(MaxClientConfig(token, eventListener = metrics))
val bot = MaxBot(client, BotConfig(eventListener = metrics))
```

Со стартером Spring Boot достаточно подключить `maxapi-micrometer`, если в приложении есть
`MeterRegistry` (например, из Actuator); остальные бины `MaxEventListener` подключаются тоже.

**Трассировка.** `maxapi-opentelemetry` записывает события как спаны OpenTelemetry; имена и атрибуты —
по семантическим конвенциям:

| Спан                      | Вид      | Атрибуты                                                                     |
|---------------------------|----------|------------------------------------------------------------------------------|
| `GET /chats/{id}`         | CLIENT   | `http.request.method`, `url.template`, `http.response.status_code`, `server.address`, `server.port`, `http.request.resend_count`, `error.type` |
| `process message_created` | CONSUMER | `messaging.system=max`, `messaging.operation.type=process`, `max.update.type`, `max.chat.id`, `max.update.handled`, `error.type` |
| `max.webhook.resubscribe` | INTERNAL | `url.full`, `error.type`                                                     |

```kotlin
val tracing = MaxTracing(openTelemetry)
val client = MaxClient(MaxClientConfig(token, eventListener = tracing))
val bot = MaxBot(client, BotConfig(eventListener = tracing))
```

Спан события начинается, когда бот его получает, и остаётся текущим в корутинах обработчика
(`MaxEventListener.updateContext` кладёт контекст OpenTelemetry в контекст корутины), поэтому запросы
обработчика к API и спаны вашего кода в нём — его дочерние спаны, в том числе после `withContext`.
Событие вебхука, обработанное через `handle`/`handleBlocking`, продолжает трассу серверного
HTTP-спана; long polling и `handleAsync` начинают новую трассу на каждое событие. Каждая HTTP-попытка —
отдельный спан, у повторов есть `http.request.resend_count`. `updateContext` подходит и для другого
контекста, например `MDCContext` из SLF4J. Со стартером Spring Boot достаточно подключить
`maxapi-opentelemetry`, если в приложении есть бин `OpenTelemetry`.

## Хранилища

По умолчанию состояния диалогов хранятся в памяти: они теряются при перезапуске, а экземпляры бота
не видят состояний друг друга. `maxapi-jdbc` и `maxapi-redis` хранят состояния диалогов, marker
polling, ключи обработанных событий, [чаты бота](#реестр-чатов) и прогресс [рассылок](#рассылки) в
SQL-базе или в Redis. Так они переживают перезапуск, общие для всех экземпляров, а повторная
доставка события отбрасывается, даже если первую получил другой экземпляр.

| Что                         | JDBC (любая база)          | Redis (Lettuce)            |
|-----------------------------|----------------------------|----------------------------|
| состояния диалогов          | `JdbcStateStorage`         | `RedisStateStorage`        |
| marker polling              | `JdbcMarkerStore`          | `RedisMarkerStore`         |
| повторные события (9 ч)     | `JdbcUpdateDeduplicator`   | `RedisUpdateDeduplicator`  |
| чаты бота                   | `JdbcChatStore`            | `RedisChatStore`           |
| прогресс рассылок           | `JdbcBroadcastStore`       | `RedisBroadcastStore`      |

```kotlin
// JDBC: любой DataSource; createTable() создаёт недостающую таблицу стандартным SQL
val bot = MaxBot(
    token,
    BotConfig(
        stateStorage = JdbcStateStorage(dataSource).createTable(),
        markerStore = JdbcMarkerStore(dataSource).createTable(),
        deduplicator = JdbcUpdateDeduplicator(dataSource).createTable(),
        chatStore = JdbcChatStore(dataSource).createTable(),
    ),
)
val broadcaster = Broadcaster(bot.client, JdbcBroadcastStore(dataSource).createTable())

// Redis: одного соединения хватает на все хранилища
val redis = RedisClient.create("redis://localhost:6379").connect()
val bot = MaxBot(
    token,
    BotConfig(
        stateStorage = RedisStateStorage(redis, ttl = Duration.ofDays(7)),
        markerStore = RedisMarkerStore(redis),
        deduplicator = RedisUpdateDeduplicator(redis),
        chatStore = RedisChatStore(redis),
    ),
)
```

JDBC-хранилища используют только стандартный SQL и проверены на H2 и PostgreSQL. По умолчанию
таблицы называются `max_dialog_state`, `max_bot_marker`, `max_seen_update`, `max_chat` и
`max_broadcast`; их `CREATE TABLE`
для миграций есть в KDoc каждого класса. Ключи Redis начинаются с `max:`. Если одну базу делят
несколько ботов, передайте другие имена таблиц, префиксы ключей или `botId`.
`JdbcStateStorage.deleteOlderThan(age)` и `ttl` у `RedisStateStorage` удаляют брошенные диалоги.

Со стартером Spring Boot подключите модуль и выберите его через `max.bot.storage`:

```yaml
max:
  bot:
    storage: jdbc              # memory (по умолчанию), jdbc или redis
    jdbc:
      table-prefix: max_       # max_dialog_state, max_bot_marker, max_seen_update, max_chat, max_broadcast
      create-tables: true      # false, если таблицы создают миграции
    redis:
      url: redis://localhost:6379
      key-prefix: "max:"
      state-ttl: 7d            # по умолчанию состояния хранятся, пока их не очистят
```

`jdbc` использует `DataSource` приложения. Дедупликаторы обоих модулей не блокируют обработчики
бота: Redis опрашивается через асинхронный API Lettuce, JDBC выполняется на `Dispatchers.IO`. С `jdbc`
и `redis` бот ещё и ведёт реестр чатов, а бин
`Broadcaster` хранит прогресс рассылок там же. Свой бин `StateStorage`, `MarkerStore`,
`UpdateDeduplicator`, `ChatStore` или `BroadcastStore` заменяет соответствующее хранилище, в том
числе без `max.bot.storage`: например, бин `InMemoryChatStore` включает реестр чатов в памяти.

## Потоки и жизненный цикл

- `MaxClient` потокобезопасен. Создайте один на токен и используйте его везде. При остановке
  вызовите `close()`, чтобы освободить HTTP-клиент и отменить незавершённые вызовы `async()`.
- `MaxBot(token)` создаёт свой клиент и владеет им; `MaxBot(client)` использует ваш.
  `MaxBot.close()` останавливает polling, отменяет выполняющиеся обработчики и закрывает клиент,
  только если бот им владеет.
- Обработчики на Kotlin выполняются в контексте корутины доставки: `Dispatchers.Default` у
  `start()`, контекст вызывающего у `startPolling()`, `processUpdate()` и `WebhookHandler.handle()`.
  Например, `runBlocking { bot.startPolling() }` выполняет все обработчики в одном потоке,
  чередуя их в точках приостановки. Не блокируйте поток в обработчиках на Kotlin; для блокирующей
  работы используйте `withContext(Dispatchers.IO)`.
- Обработчики на Java всегда выполняются на `Dispatchers.IO` и могут блокировать поток.
- Методы `*Blocking` используют `runBlocking`. Вызывайте их из обработчиков на Java или обычных
  потоков, а не из корутин.

## Структура проекта

```
maxapi/                        библиотека
  src/main/kotlin/ru/securitytrip/maxapi/
    model/    модели API по спецификации, подпакет на каждый раздел:
              user, chat, message, attachment, keyboard, markup, update, subscription
    client/   MaxClient, настройки, исключения, JSON, загрузка файлов, HTTP-транспорт
    java/     фасады MaxBlockingClient и MaxAsyncClient
    dsl/      билдеры сообщений и клавиатур, Buttons
    bot/      MaxBot, роутеры, фильтры, контексты, состояние диалога, long polling, WebhookHandler,
              сцены, клавиатуры, переводы, реестр чатов, рассылки
    webapp/   мини-приложения: проверка initData, телефона и контакта, диплинки
  src/test/   тесты (Kotlin и Java) на имитации HTTP-сервера
  api/        дамп публичного API для проверки бинарной совместимости
  spec/       спецификация Max Bot API, с которой сверяется библиотека
  docs/       описания модуля и пакетов для справочника API
maxapi-spring-boot-starter/    автоконфигурация Spring Boot
maxapi-ktor/                   маршрутизация вебхуков и плагин мини-приложений для Ktor
maxapi-test/                   BotTester и TestUpdates
maxapi-micrometer/             MaxMetrics
maxapi-jdbc/                   JDBC-хранилища (тесты на H2 и PostgreSQL)
maxapi-redis/                  Redis-хранилища (тесты на Redis)
maxapi-opentelemetry/          MaxTracing
maxapi-bom/                    BOM с версиями модулей
build-logic/                   convention-плагины: настройки Kotlin, покрытие, Dokka, публикация
examples/, examples-spring/, examples-ktor/
integration-tests/             приложения Spring Boot целиком (MVC и WebFlux, Actuator): фейковый Max, PostgreSQL и Redis
```

Пакеты `client` и `model` не зависят от `bot`, поэтому клиент можно использовать отдельно.

## Сборка и тесты

```bash
./gradlew build
```

Команда компилирует все модули и примеры и запускает тесты. Тесты используют `MockWebServer` из
OkHttp, context runner'ы Spring и тестовый хост Ktor, а тесты хранилищ и стартера поднимают
PostgreSQL и Redis через Testcontainers, поэтому для них нужен Docker. Токен и доступ к API не
нужны. Каждый метод API проверяется через все три фасада (Kotlin, блокирующий, асинхронный), как и
каждый тип события, вложения, кнопки и разметки.

Модуль `integration-tests` запускает приложение Spring Boot целиком против фейкового сервера Max:
long polling с перезапуском посреди диалога на PostgreSQL и на Redis (диалог продолжается, polling
идёт с сохранённого marker, повторная доставка отбрасывается) и вебхук на настоящем Tomcat с
проверкой секрета и дедупликацией. Отдельно их можно запустить командой
`./gradlew :integration-tests:test`.

У каждого модуля библиотеки 100% покрытия строк, и `./gradlew check` (входит в `build`) падает,
если изменение его снижает или если покрытие ветвей опускается ниже 95% (Kotlin генерирует
несколько недостижимых проверок на null). Отчёты о покрытии (Kover) пишутся в
`<module>/build/reports/kover/html/index.html`:

```bash
./gradlew koverHtmlReport
```

Публичный API каждого модуля записан в `<module>/api/<module>.api`, и `./gradlew check` падает,
когда он меняется. После намеренного изменения обновите дампы и закоммитьте их вместе с кодом,
чтобы изменение было видно на ревью:

```bash
./gradlew apiDump
```

Библиотека сверяется со спецификацией API в [maxapi/spec/schema.yaml](maxapi/spec/schema.yaml):
`SpecConformanceTest` сравнивает её методы, типы событий, вложений, кнопок, разметки и перечисления с
клиентом и моделями. Чтобы поддержать новую версию Max Bot API, замените этот файл и запустите
тесты: упавшие проверки покажут, что нужно добавить.

Если тест записан как `fun name() = runBlocking { ... }`, укажите тип возврата явно:
`fun name(): Unit = runBlocking { ... }`. Иначе тест, последнее выражение которого возвращает
значение (например, `assertIs<T>(...)`), получит тип возврата не `Unit`, и JUnit молча его
пропустит.

Справочник API генерируется [Dokka](https://github.com/Kotlin/dokka) из KDoc-комментариев и
описаний пакетов в [maxapi/docs/module.md](maxapi/docs/module.md):

```bash
./gradlew :dokkaGenerate
```

Справочник всех модулей — в `build/dokka/html/index.html`. `-javadoc.jar` каждого модуля содержит
его часть, а каждый стабильный релиз публикует весь справочник на
[securitytrip.github.io/maxapi](https://securitytrip.github.io/maxapi/).

Запуск примеров на настоящем боте:

```bash
MAX_BOT_TOKEN=your-token ./gradlew :examples:run
```

```bash
MAX_BOT_TOKEN=your-token ./gradlew :examples:run -PmainClass=ru.securitytrip.maxapi.examples.JavaEchoBot
```

## Выпуск релизов

Релизы публикуются в Maven Central [workflow Release](.github/workflows/release.yml), когда на
GitHub публикуется релиз. Каждый push и pull request проверяется [workflow CI](.github/workflows/ci.yml).

### Первоначальная настройка

1. Войдите на [central.sonatype.com](https://central.sonatype.com/) через GitHub-аккаунт
   `SecurityTrip`. Пространство имён `io.github.securitytrip` подтверждается для него
   автоматически; проверьте, что оно есть в **Namespaces**.
2. Создайте пользовательский токен в **Account → Generate User Token**. Он состоит из имени
   пользователя и пароля.
3. Создайте GPG-ключ для подписи и опубликуйте его публичную часть, чтобы Maven Central мог
   проверять подписи:

   ```bash
   gpg --quick-gen-key "SecurityTrip <your-email>" rsa4096 sign 3y
   gpg --list-secret-keys --keyid-format=long          # запомните id ключа
   gpg --keyserver keyserver.ubuntu.com --send-keys <key-id>
   gpg --armor --export-secret-keys <key-id>           # значение для GPG_SIGNING_KEY
   ```

4. Добавьте секреты репозитория в **Settings → Secrets and variables → Actions**:

   | Секрет                       | Значение                                       |
   |------------------------------|------------------------------------------------|
   | `MAVEN_CENTRAL_USERNAME`     | имя пользователя из токена                     |
   | `MAVEN_CENTRAL_PASSWORD`     | пароль из токена                               |
   | `GPG_SIGNING_KEY`            | секретный ключ в armored-формате, вместе с BEGIN/END |
   | `GPG_SIGNING_KEY_PASSWORD`   | пароль ключа (не нужен, если его нет)          |

5. Включите GitHub Pages для справочника API: **Settings → Pages → Build and deployment → Source:
   GitHub Actions**. Workflow Release публикует из тега релиза, поэтому разрешите и теги:
   **Settings → Environments → github-pages → Deployment branches and tags → Add deployment
   branch or tag rule**, тип **Tag**, шаблон `v*`. Без этого задача `docs` падает с ошибкой
   «not allowed to deploy to github-pages due to environment protection rules».

### Публикация версии

1. Перенесите записи из `## [Unreleased]` в [CHANGELOG.md](CHANGELOG.md) в новый раздел
   `## [1.2.3] - YYYY-MM-DD`, обновите ссылки внизу файла и влейте это в `master`. Без раздела
   своей версии workflow публиковать её откажется.
2. Создайте релиз на GitHub с тегом `v1.2.3` (или `v1.2.3-rc1`, отмеченный как pre-release).

Workflow берёт версию из тега, собирает и тестирует её, подписывает артефакты всех модулей (включая
BOM) и публикует их одним deployment; Maven Central выпускает их после проверки, и обычно в поиске
они появляются в течение часа. Затем workflow заменяет описание релиза разделом из CHANGELOG и для
стабильных релизов публикует справочник API на GitHub Pages.

У Maven Central есть бесплатный месячный лимит на число релизов и файлов на организацию; текущее
использование видно в Usage Center на central.sonatype.com. Поэтому кандидаты (`-rc`) лучше
проверять локально, а не публиковать.

Если публикация упала после создания релиза, устраните причину и запустите workflow Release вручную
(**Actions → Release → Run workflow**) с той же версией. Версию, попавшую в Maven Central, заменить
нельзя: выпустите новую.

## Участие в разработке

См. [CONTRIBUTING.md](CONTRIBUTING.md).

## Лицензия

Copyright 2026 SecurityTrip

Распространяется по [лицензии Apache, версия 2.0](LICENSE).
