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
- [Ktor](#ktor)
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
- Загрузка файлов одним вызовом; токен бота никогда не отправляется на хост загрузки.
- Автоматические повторы с экспоненциальной задержкой при 429/503, а для идемпотентных запросов —
  и при 502/504 и сетевых ошибках; повторная отправка, пока вложение ещё обрабатывается
  (`attachment.not.ready`).
- Правила платформы: лимиты Max (длина текста, размер клавиатуры, кнопки, команды) проверяются до
  отправки, длинный текст уходит несколькими сообщениями без поломки разметки, `RateLimit.MAX`
  соблюдает лимиты частоты запросов, а стартер возобновляет подписку на вебхук, если Max её снял.
- Long polling с ограниченной параллельностью, порядком внутри чата и обратным давлением.
- Обработчик вебхуков с проверкой секрета за постоянное время, который подключается к Spring, Ktor,
  Javalin или сервлетам.
- Мало зависимостей: OkHttp, Jackson, kotlinx.coroutines. Логи пишутся в `System.Logger` и через
  обычные мосты попадают в SLF4J, Log4j или JUL.
- Стартер Spring Boot (polling или вебхук из `application.yaml`), маршрутизация вебхуков для Ktor,
  тестовый набор, который запускает обработчики без сети, и хранилища в SQL или Redis.

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
параметр (до 128 символов) в событии `bot_started`:

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
    implementation("org.springframework.boot:spring-boot-starter-web") // только для режима webhook
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
      path: /max/webhook     # endpoint в вашем Spring MVC-сервере
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
| `webhook` | `POST max.bot.webhook.path` принимает доставки (сервлетные веб-приложения); если задан `webhook.url`, бот подписывается при запуске и каждые `webhook.check-interval` проверяет, что подписка на месте, а если Max её снял, подписывается заново |
| `none`    | ничего не запускается; доставляйте события сами                                           |

`max.bot.rate-limit=max` включает `RateLimit.MAX` (см. [Надёжность](#надёжность-и-мониторинг)); по
умолчанию ограничения нет, как и в предыдущих версиях. Бины `MaxEventListener` (и `MaxMetrics`, если
подключён `maxapi-micrometer` и есть `MeterRegistry`) получают события клиента и бота. Бины `StateStorage`, `MarkerStore` и `UpdateDeduplicator`
используются ботом, см. [Хранилища](#хранилища).

Endpoint вебхука отвечает 200 на принятые события, 401 на неверный секрет, 400 на тело, которое не
является событием, и 503, когда бот закрыт. Чтобы заменить бин по умолчанию, объявите свой
`MaxClient`, `MaxBot` или `BotConfig`; бины `MaxClientConfigCustomizer` меняют настройки клиента
(политику повторов, `OkHttpClient`).

Полное приложение — в [examples-spring](examples-spring).

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
не получает событий. См. [examples-ktor](examples-ktor).

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
- `TestUpdates` собирает другие события: `TestUpdates.message("hi", chatType = ChatType.CHAT)`.

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

## Хранилища

По умолчанию состояния диалогов хранятся в памяти: они теряются при перезапуске, а экземпляры бота
не видят состояний друг друга. `maxapi-jdbc` и `maxapi-redis` хранят состояния диалогов, marker
polling и ключи обработанных событий в SQL-базе или в Redis. Так они переживают перезапуск, общие
для всех экземпляров, а повторная доставка события отбрасывается, даже если первую получил другой
экземпляр.

| Что                         | JDBC (любая база)          | Redis (Lettuce)            |
|-----------------------------|----------------------------|----------------------------|
| состояния диалогов          | `JdbcStateStorage`         | `RedisStateStorage`        |
| marker polling              | `JdbcMarkerStore`          | `RedisMarkerStore`         |
| повторные события (9 ч)     | `JdbcUpdateDeduplicator`   | `RedisUpdateDeduplicator`  |

```kotlin
// JDBC: любой DataSource; createTable() создаёт недостающую таблицу стандартным SQL
val bot = MaxBot(
    token,
    BotConfig(
        stateStorage = JdbcStateStorage(dataSource).createTable(),
        markerStore = JdbcMarkerStore(dataSource).createTable(),
        deduplicator = JdbcUpdateDeduplicator(dataSource).createTable(),
    ),
)

// Redis: одного соединения хватает на все три
val redis = RedisClient.create("redis://localhost:6379").connect()
val bot = MaxBot(
    token,
    BotConfig(
        stateStorage = RedisStateStorage(redis, ttl = Duration.ofDays(7)),
        markerStore = RedisMarkerStore(redis),
        deduplicator = RedisUpdateDeduplicator(redis),
    ),
)
```

JDBC-хранилища используют только стандартный SQL и проверены на H2 и PostgreSQL. По умолчанию
таблицы называются `max_dialog_state`, `max_bot_marker` и `max_seen_update`; их `CREATE TABLE`
для миграций есть в KDoc каждого класса. Ключи Redis начинаются с `max:`. Если одну базу делят
несколько ботов, передайте другие имена таблиц, префиксы ключей или `botId`.
`JdbcStateStorage.deleteOlderThan(age)` и `ttl` у `RedisStateStorage` удаляют брошенные диалоги.

Со стартером Spring Boot подключите модуль и выберите его через `max.bot.storage`:

```yaml
max:
  bot:
    storage: jdbc              # memory (по умолчанию), jdbc или redis
    jdbc:
      table-prefix: max_       # max_dialog_state, max_bot_marker, max_seen_update
      create-tables: true      # false, если таблицы создают миграции
    redis:
      url: redis://localhost:6379
      key-prefix: "max:"
      state-ttl: 7d            # по умолчанию состояния хранятся, пока их не очистят
```

`jdbc` использует `DataSource` приложения. Свой бин `StateStorage`, `MarkerStore` или
`UpdateDeduplicator` заменяет соответствующее хранилище, в том числе без `max.bot.storage`.

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
    bot/      MaxBot, роутеры, фильтры, контексты, состояние диалога, long polling, WebhookHandler
  src/test/   тесты (Kotlin и Java) на имитации HTTP-сервера
  api/        дамп публичного API для проверки бинарной совместимости
  spec/       спецификация Max Bot API, с которой сверяется библиотека
  docs/       описания модуля и пакетов для справочника API
maxapi-spring-boot-starter/    автоконфигурация Spring Boot
maxapi-ktor/                   маршрутизация вебхуков в Ktor
maxapi-test/                   BotTester и TestUpdates
maxapi-micrometer/             MaxMetrics
maxapi-jdbc/                   JDBC-хранилища (тесты на H2 и PostgreSQL)
maxapi-redis/                  Redis-хранилища (тесты на Redis)
maxapi-bom/                    BOM с версиями модулей
build-logic/                   convention-плагины: настройки Kotlin, покрытие, Dokka, публикация
examples/, examples-spring/, examples-ktor/
integration-tests/             приложение Spring Boot целиком: фейковый Max, PostgreSQL и Redis
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
