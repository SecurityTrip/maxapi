# maxapi

[![CI](https://github.com/SecurityTrip/maxapi/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/SecurityTrip/maxapi/actions/workflows/ci.yml)
[![Release](https://github.com/SecurityTrip/maxapi/actions/workflows/release.yml/badge.svg?event=release)](https://github.com/SecurityTrip/maxapi/actions/workflows/release.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.securitytrip/maxapi?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.securitytrip/maxapi)
[![Line coverage](https://img.shields.io/badge/line%20coverage-100%25-brightgreen)](#building-and-testing)
[![API reference](https://img.shields.io/badge/docs-API%20reference-blue)](https://securitytrip.github.io/maxapi/)
[![License](https://img.shields.io/github/license/SecurityTrip/maxapi)](LICENSE)

[Русский](README.md) | **English**

A Kotlin and Java library for building bots for the [Max](https://max.ru) messenger on top of
[Max Bot API](https://dev.max.ru/docs-api).

It consists of two layers that can be used separately:

- **API client** (`MaxClient`): every method of the Bot API (specification 0.0.33) with typed models.
  Kotlin gets `suspend` functions; Java gets blocking and `CompletableFuture` facades.
- **Bot framework** (`MaxBot`): update handlers for commands, messages and button presses,
  long polling with per-chat ordering, and a webhook handler for any HTTP server.

```kotlin
val bot = MaxBot(System.getenv("MAX_BOT_TOKEN"))
bot.onCommand("start") { reply("Hello, ${user?.firstName}!") }
bot.onMessage { replyToMessage(text ?: "I only understand text") }
bot.startPolling()
```

## Contents

- [Features](#features)
- [Requirements and installation](#requirements-and-installation)
- [Compatibility](#compatibility)
- [Getting a token](#getting-a-token)
- [Quick start](#quick-start)
- [Bot framework](#bot-framework)
  - [Handlers and routing](#handlers-and-routing)
  - [Handler contexts](#handler-contexts)
  - [Commands](#commands)
  - [Buttons and callbacks](#buttons-and-callbacks)
  - [Deep links](#deep-links)
  - [Other updates](#other-updates)
  - [Filters](#filters)
  - [Middleware](#middleware)
  - [Routers](#routers)
  - [Dialog state](#dialog-state)
  - [Error handling](#error-handling)
  - [Long polling](#long-polling)
  - [Webhooks](#webhooks)
- [API client](#api-client)
  - [Calling methods](#calling-methods)
  - [Using the client from Java](#using-the-client-from-java)
  - [Building messages](#building-messages)
  - [Formatting](#formatting)
  - [Uploading files](#uploading-files)
  - [Pagination](#pagination)
- [Spring Boot](#spring-boot)
- [Ktor](#ktor)
- [Mini apps](#mini-apps)
  - [Validating initData](#validating-initdata)
  - [Phone number from requestContact](#phone-number-from-requestcontact)
  - [Contact from a request_contact button](#contact-from-a-request_contact-button)
  - [Link builders](#link-builders)
  - [Spring MVC backend](#spring-mvc-backend)
  - [Ktor backend](#ktor-backend)
- [Testing bots](#testing-bots)
- [Models](#models)
- [Errors and retries](#errors-and-retries)
- [Configuration](#configuration)
- [Reliability and monitoring](#reliability-and-monitoring)
- [Storage](#storage)
- [Threading and lifecycle](#threading-and-lifecycle)
- [Project layout](#project-layout)
- [Building and testing](#building-and-testing)
- [Releasing](#releasing)
- [Contributing](#contributing)
- [License](#license)

## Features

- All 33 operations of the specification, named after their `operationId`s, so the
  [official documentation](https://dev.max.ru/docs-api) applies directly.
- Typed, immutable models. Updates, attachments, buttons and text markup are sealed hierarchies:
  use `when (update) { is MessageCreatedUpdate -> ... }` in Kotlin or `instanceof` in Java.
- Forward compatible: update, attachment, button and markup types the library does not know yet
  are parsed into `Unknown*` objects with all their fields, and unknown enum values become `UNKNOWN`.
  New server features never break deserialization.
- First-class Java support: blocking and `CompletableFuture` facades, builders, `@JvmStatic`
  factories, Java overloads for optional parameters, unchecked exceptions only.
- Kotlin DSL for messages and keyboards.
- Bot building blocks: composable filters, middleware, routers for feature modules, dialog states
  with pluggable storage, typed callback payloads, the command menu built from handlers.
- File uploads in one call; the bot token is never sent to the upload host.
- Automatic retries with exponential backoff on 429/503, and on 502/504 and network errors of
  idempotent requests, plus re-sending while an attachment is still being processed (`attachment.not.ready`).
- Platform rules: the limits of Max (text length, keyboard size, buttons, commands) are checked
  before sending, long text goes as several messages with its markup intact, `RateLimit.MAX` keeps
  to the request rate limits, and the starter renews a webhook subscription that Max dropped.
- Mini app backends: validation of `initData`, of the phone number from `requestContact()` and of
  the contact from a `request_contact` button with the formulas of Max, deep link builders, a
  `@MaxWebAppUser` argument for Spring MVC and a `MaxWebApp` plugin for Ktor.
- Long polling with bounded parallelism, per-chat ordering and backpressure.
- Webhook handler with constant-time secret verification that plugs into Spring, Ktor, Javalin or servlets.
- Few dependencies: OkHttp, Jackson, kotlinx.coroutines. Logging goes to `System.Logger`, so it
  reaches SLF4J, Log4j or JUL through their usual bridges.
- Spring Boot starter (polling or webhook from `application.yaml`), Ktor webhook routing, a test
  kit that runs handlers without a network, and storage in SQL or Redis.

## Requirements and installation

- JDK 17 or newer.
- Kotlin 2.x if you use the library from Kotlin.

The library is published to Maven Central under the group `io.github.securitytrip`
(see [releases](https://github.com/SecurityTrip/maxapi/releases) for the latest version):

| Artifact                     | Contents                                                       |
|------------------------------|----------------------------------------------------------------|
| `maxapi`                     | API client and bot framework                                   |
| `maxapi-spring-boot-starter` | Spring Boot auto-configuration, see [Spring Boot](#spring-boot) |
| `maxapi-ktor`                | Ktor webhook routing, see [Ktor](#ktor)                        |
| `maxapi-test`                | test kit, see [Testing bots](#testing-bots)                    |
| `maxapi-micrometer`          | Micrometer metrics, see [Reliability and monitoring](#reliability-and-monitoring) |
| `maxapi-jdbc`                | dialog states, marker and seen updates in an SQL database, see [Storage](#storage) |
| `maxapi-redis`               | the same in Redis (Lettuce), see [Storage](#storage)            |
| `maxapi-bom`                 | versions of all of the above                                   |

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

OkHttp, Jackson and kotlinx.coroutines come in as transitive dependencies. Packages in the code
are `ru.securitytrip.maxapi.*`.

To try unreleased changes, install the current sources to the local Maven repository with
`./gradlew publishToMavenLocal` and depend on the `-SNAPSHOT` version from
[maxapi-publishing](build-logic/src/main/kotlin/maxapi.publishing.gradle.kts) with `mavenLocal()`
among the repositories.

## Compatibility

Since 1.0 the public API follows [semantic versioning](https://semver.org/): minor and patch
versions keep source and binary compatibility, and API is deprecated (with `ReplaceWith`) before it
is removed in the next major version. Every module's public API is recorded in `<module>/api/` and
checked on every build. Coming from 0.x? See [MIGRATION.md](MIGRATION.md).

| Component         | Supported                                              |
|-------------------|--------------------------------------------------------|
| Java              | 17 or newer                                            |
| Kotlin            | 2.x (the library is built with 2.4)                    |
| Max Bot API       | specification 0.0.33; newer server features arrive as `Unknown*` objects |
| Spring Boot       | 4.x (built and tested with 4.1)                        |
| Ktor              | 3.x (built and tested with 3.6)                        |
| Micrometer        | 1.x (built and tested with 1.17)                       |
| Lettuce (Redis)   | 7.x (built and tested with 7.5 and Redis 7.4)          |
| JDBC              | any database with standard SQL (tested on H2 2.4 and PostgreSQL 17) |

Not part of the compatible API: anything `internal`, the content of exception messages and log
lines, and the exact defaults of `BotTester` responses.

## Getting a token

Create a bot on [business.max.ru](https://business.max.ru/) and copy its access token. The library
sends it in the `Authorization` header of every request. Keep it out of source code; the examples
read it from the `MAX_BOT_TOKEN` environment variable. `MaxClientConfig.toString()` never prints the token.

## Quick start

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

        bot.startPolling() // runs until the coroutine is cancelled
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

            bot.startBlocking(); // blocks until bot.stop() or a fatal error
        }
    }
}
```

Complete runnable versions are in [examples](examples).

## Bot framework

### Handlers and routing

Register handlers with the `on*` methods of `MaxBot`:

| Method                         | Handles                                                    | Context              |
|--------------------------------|------------------------------------------------------------|----------------------|
| `onCommand("name")`            | messages starting with `/name`                             | `MessageContext`     |
| `onCommand("name", "description")` | the same, and adds the command to the bot menu         | `MessageContext`     |
| `onMessage { }`                | any new message                                            | `MessageContext`     |
| `onMessage(filter) { }`        | new messages matching a predicate                          | `MessageContext`     |
| `onCallback("payload") { }`    | presses of buttons with this exact payload                 | `CallbackContext`    |
| `onCallback(filter) { }`       | button presses matching a predicate                        | `CallbackContext`    |
| `onCallback(callbackData) { }` | presses of buttons with payloads of a `CallbackData` format | `CallbackContext`   |
| `onCallback { }`               | any button press                                           | `CallbackContext`    |
| `onBotStarted { }`             | a user pressing "Start" or opening a deep link             | `BotStartedContext`  |
| `on<T> { }` / `onUpdate(T)`    | any update of type `T`; `Update` matches everything        | `UpdateContext<T>`   |
| `onError { error, update -> }` | exceptions thrown by handlers and polling errors           | —                    |
| `use { ctx, next -> }`         | middleware around the handlers                             | any                  |
| `include(router)`              | adds the handlers of a `Router`                            | —                    |

**Handlers are checked in registration order, and only the first matching one runs.** Register
specific handlers before catch-all ones:

```kotlin
bot.onCommand("start") { ... }                        // 1. /start
bot.onMessage({ it.text == "ping" }) { reply("pong") } // 2. exact text
bot.onMessage { ... }                                  // 3. everything else
```

Updates that no handler matches are ignored. The bot asks the server only for the update types it
has handlers for, which saves traffic.

Every registration method has two overloads:

- Kotlin: a `suspend` lambda with the context as the receiver: `bot.onCommand("start") { reply("Hi") }`;
- Java: a `Handler<Context>` that runs on `Dispatchers.IO`, so blocking calls are fine there:
  `bot.onCommand("start", ctx -> ctx.replyBlocking("Hi"))`.

Registration methods return the bot, so calls can be chained. Register all handlers before
starting polling or accepting webhooks.

### Handler contexts

A handler receives a context with the update and shortcuts to answer it.

`UpdateContext<U>` (base of all contexts):

| Member                         | Description                                                           |
|--------------------------------|-----------------------------------------------------------------------|
| `update`                       | the update being handled                                              |
| `bot`, `client`, `blocking()`  | the bot, its `MaxClient` and the blocking facade                      |
| `chatId`, `user`               | chat and user of the update, when known                               |
| `locale`                       | locale of the user's client (`ru`, `en`, ...), when the update has it |
| `state`, `stateData`           | dialog state and its values, see [Dialog state](#dialog-state)        |
| `setState`, `updateStateData`, `clearState` | change the dialog state                                  |
| `reply(text)`, `reply(body)`, `reply { DSL }` | send a message to the chat (or to the user if there is no chat) |
| `replyLong(text)`, `replyLong(body)`, `replyLong { DSL }` | the same for text of any length, see [Long text](#long-text) |
| `sendAction(action)`, `typing()` | show "typing…" or another activity                                  |
| `replyBlocking`, `replyLongBlocking`, `sendActionBlocking`, `setStateBlocking`, ... | Java versions |

`MessageContext` adds `message`, `text`, `messageId`, `command`, `commandArgs`,
`replyToMessage(...)` (reply with a quote) and `replyToMessageBlocking(...)`. `text` is `null` for
messages without text: Max sends an empty string for photos and stickers, which `text` turns into
`null`, so `text ?: "..."` and `Filters.hasText()` work as expected.

`CallbackContext` adds `callback`, `payload`, `message` (the message with the keyboard),
`data(callbackData)`, `answer(notification)`, `answer(CallbackAnswer)`,
`editMessage(body, notification)` and their `*Blocking` versions.

`BotStartedContext` adds `payload`, the deep link parameter.

Any API method is available through `client`, e.g. `client.pinMessage(chatId!!, messageId!!)`.

### Commands

`onCommand("start")` matches `/start`, `/Start` and `/start with arguments`; the leading `/` in the
name is optional. A command with a suffix, like `/start@my_bot`, is handled only when the suffix is
this bot's username, so in group chats commands for other bots are left alone. The username is
fetched with `getMyInfo` the first time such a command arrives; set `bot.username` to skip that call.

```kotlin
bot.onCommand("weather") {
    val city = commandArgs.ifBlank { "Moscow" } // "/weather Kazan" -> "Kazan"
    reply("Weather in $city: ...")
}
```

Commands registered with a description make up the bot menu. `syncCommands()` sends them
(including those of included routers) with `editMyCommands`; call it once on startup:

```kotlin
bot.onCommand("start", "Start the bot") { ... }
bot.onCommand("weather", "Weather forecast") { ... }
bot.syncCommands() // bot.commands() returns the list
```

The mobile apps of Max show this menu; the web client does not show it, so there users type
commands themselves.

### Buttons and callbacks

Keyboards are attached to messages. Button kinds:

| Factory (`Buttons.*` / row DSL)       | What happens on press                                  |
|---------------------------------------|--------------------------------------------------------|
| `callback(text, payload)`             | the bot receives a callback with `payload`             |
| `link(text, url)`                     | the user opens `url`                                   |
| `message(text)`                       | the user sends `text` to the chat                      |
| `requestContact(text)`                | the user shares their contact (`ContactAttachment`)    |
| `requestGeoLocation(text, quick)`     | the user shares their location (`LocationAttachment`)  |
| `openApp(text, webApp, payload)`      | a mini app opens                                       |
| `clipboard(text, payload)`            | `payload` is copied to the clipboard                   |

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

// Answer every press: either update the message, or show a notification, or both.
bot.onCallback("orders") { editMessage(message { text("You have no orders yet") }) }
bot.onCallback("settings") { answer("Settings are coming soon") }

// Parameterized payloads
bot.onCallback(Filters.payloadStartsWith("order:")) {
    val id = payload!!.removePrefix("order:")
    answer("Order $id")
}
```

`CallbackData` describes such payloads once, packs and parses them, and checks the
1024-character limit of the API:

```kotlin
val order = CallbackData("order", "id", "action")    // payloads like "order:42:buy"

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

A notification (`answer(notification)`) is a short popup in the mobile apps of Max; the web client
does not show it. When the user must see the result, also change the message with
`editMessage(body, notification)` or send a new one.

From Java, add rows with `keyboardRow(...)`:

```java
NewMessageBody.builder()
        .text("Main menu")
        .keyboardRow(Buttons.callback("Orders", "orders"), Buttons.callback("Settings", "settings"))
        .keyboardRow(Buttons.link("Website", "https://example.com"))
        .build();
```

### Deep links

A link `https://max.ru/<botName>?start=<payload>` opens the dialog with the bot and delivers the
payload (up to 128 characters) in a `bot_started` update. `MaxLinks.botStart("shop_bot", "ref-42")`
builds such a link, see [Link builders](#link-builders).

```kotlin
bot.onBotStarted {
    when (val ref = payload) {
        null -> reply("Welcome!")
        else -> reply("Welcome! You came from $ref")
    }
}
```

### Other updates

Every update type has its own class in `ru.securitytrip.maxapi.model.update`. Handle any of them with `on<T>`:

```kotlin
bot.on<BotAddedToChatUpdate> { reply("Hi everyone! Thanks for adding me, ${update.user.firstName}") }
bot.on<MessageEditedUpdate> { println("Edited: ${update.message.body?.text}") }
bot.on<UserAddedToChatUpdate> { reply("Welcome, ${update.user.firstName}!") }
```

```java
bot.onUpdate(BotAddedToChatUpdate.class, ctx -> ctx.replyBlocking("Hi everyone!"));
```

In group chats the bot receives messages and member events only if it is an administrator with the
`read_all_messages` permission.

### Filters

`Filters` has ready-made conditions for the `on*` methods that take a filter. In Kotlin combine
them with `and`, `or` and `!`; in Java with the `Predicate` methods `and`, `or` and `negate`.

| Filter                                           | Matches                                         |
|--------------------------------------------------|-------------------------------------------------|
| `hasText()`, `text(Regex)`, `text(Pattern)`      | messages with text, text containing a match     |
| `textEquals(...)`, `textEqualsIgnoreCase(...)`, `textStartsWith(prefix)` | message text            |
| `chatType(ChatType.DIALOG, ...)`                 | messages in dialogs, group chats or channels    |
| `hasAttachment<PhotoAttachment>()`, `hasAttachment(Class)` | messages with an attachment of a type |
| `payloadStartsWith(prefix)`                      | button presses                                  |
| `fromUsers(ids)`, `inChats(ids)`                 | any update from these users or chats            |
| `state(names)`, `noState()`, `anyState()`        | any update, by [dialog state](#dialog-state)    |
| `Filters.of { ... }`                             | your own condition                              |

```kotlin
bot.onMessage(Filters.chatType(ChatType.DIALOG) and Filters.hasAttachment<PhotoAttachment>()) { reply("Nice photo!") }
bot.onMessage(Filters.fromUsers(*admins) and Filters.textStartsWith("/admin")) { ... }
```

```java
// In Java, start with the most specific filter: and() accepts filters of the same or a more general context.
bot.onMessage(Filters.chatType(ChatType.DIALOG).and(Filters.fromUsers(42L)), ctx -> ...);
```

Filters of any update (`fromUsers`, `state`, ...) fit `onMessage`, `onCallback` and the others.
A lambda works wherever a filter does: `bot.onMessage({ (it.text?.length ?: 0) > 100 }) { ... }`.

### Middleware

Middleware runs around handlers: logging, timing, access checks, error translation. It receives
the context of the matched handler and `next`, which runs the handler; not calling `next` skips it.
The first registered middleware is the outermost. Middleware runs only for updates some handler matched.

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
    if (isBanned(ctx.getUser())) return; // the handler does not run
    next.proceed();
});
```

### Routers

A `Router` is a group of handlers with its own middleware. Use routers to split a bot into
feature modules and to apply middleware to some handlers only:

```kotlin
val admin = Router()
    .use { ctx, next -> if (ctx.user?.userId in admins) next() else ctx.reply("Admins only") }
    .onCommand("ban", "Ban a user") { ... }
    .onCommand("stats", "Statistics") { ... }

bot.onCommand("start") { ... }
bot.include(admin)            // checked after /start and before the catch-all handler
bot.onMessage { ... }
```

Handlers of an included router take part in the usual "first match wins" order at the place of
`include`. Bot middleware wraps the router's handlers too; router middleware wraps only its own
handlers and those of the routers it includes.

### Dialog state

For conversations of several steps (forms, wizards, checkout), every dialog, meaning a user in a
chat, has an optional state: a name and string values. Handlers read it as `state` and
`stateData` and change it with `setState`, `updateStateData` and `clearState`; `Filters.state`
routes by it:

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

States live in `BotConfig.stateStorage`. The default `InMemoryStateStorage` loses them on restart;
ready-made stores in a database and in Redis are described in [Storage](#storage). A store of your
own implements `StateStorage` (Kotlin, `suspend` methods) or extends `BlockingStateStorage` (Java,
runs on `Dispatchers.IO`):

```kotlin
val bot = MaxBot(token, BotConfig(stateStorage = RedisStateStorage(redis)))
```

The bot reads the state once per update before filters run, so filters see it without I/O.

### Error handling

Exceptions thrown by handlers never stop the bot. They are passed to the error handler together
with the update; polling errors arrive with `update = null`. By default errors are logged to
`System.Logger` under the name `ru.securitytrip.maxapi.bot`.

```kotlin
bot.onError { error, update ->
    log.error("Failed to handle ${update?.updateType}", error)
}
```

```java
bot.onError((error, update) -> log.error("Failed to handle update", error));
```

### Long polling

| Method               | Use from | Behavior                                                         |
|----------------------|----------|------------------------------------------------------------------|
| `startPolling()`     | Kotlin   | `suspend`; runs until `stop()` or until the coroutine is cancelled |
| `start()`            | both     | starts in the background and returns a `Job`                     |
| `startBlocking()`    | Java     | blocks the thread until `stop()` or a fatal error                |
| `stop()`             | both     | stops requesting updates; updates already received still finish  |
| `close()`            | both     | cancels everything immediately, including queued updates         |

Updates that were received are already considered delivered by the server, so on shutdown call
`stop()` and wait for polling to end (`startBlocking()` returns, the `Job` of `start()` completes)
before `close()`, otherwise those updates are lost.

How it works:

- Updates are distributed among `BotConfig.parallelism` workers (4 by default) **by chat**: updates of
  one chat are always handled one at a time and in order, different chats are handled in parallel.
- Each worker has a bounded queue (`queueCapacity`); when it is full, polling waits.
- Network and server errors are reported to `onError` and retried with a pause that doubles from
  1 second up to `maxErrorBackoff`.
- An invalid token (`UnauthorizedException`) and an active webhook subscription (`MaxException`)
  stop polling: `startPolling()` and `startBlocking()` throw them. While the bot is subscribed to a
  webhook, Max answers long polling with empty pages, so polling checks the subscriptions before it
  starts instead of silently receiving nothing. `deleteWebhook()` removes them, e.g. after a webhook
  server that did not unsubscribe on shutdown.

```kotlin
val bot = MaxBot(token, BotConfig(parallelism = 16, pollTimeoutSeconds = 60))
```

Long polling is limited in speed and in how long updates are kept; the API recommends webhooks for
production. Polling and webhooks cannot be used at the same time.

### Webhooks

1. Subscribe once (for example on deploy). Only the update types the bot handles are requested:

   ```kotlin
   bot.setWebhook("https://example.com/max/webhook", secret = "my-secret-123")
   ```

   The URL must be HTTPS on port 443. The secret (5–256 characters of `[A-Za-z0-9_-]`) is sent in
   the `X-Max-Bot-Api-Secret` header of every request. Register handlers before calling it:
   `setWebhook` throws if there are none, because the subscription would get no useful updates.

2. Pass requests from your HTTP server to a `WebhookHandler`:

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

`WebhookHandler` methods:

| Method                   | Behavior                                                                    |
|--------------------------|-----------------------------------------------------------------------------|
| `handle` (`suspend`)     | verifies the secret, parses the update and runs the handler before returning |
| `handleBlocking`         | the same, blocking; for Java and servlet containers                         |
| `handleAsync`            | verifies and parses immediately, runs the handler in the background         |

All of them return `false` when the secret does not match (respond with 401), and `true` otherwise
(respond with 200). Max retries a delivery until it gets `200 OK`, for up to 8 hours, so respond with
200 even if your handler failed; the failure goes to `onError`. With `handleAsync` updates of one chat
may be handled out of order, and after `bot.close()` it throws `IllegalStateException`: respond with
an error then, so that Max delivers the update again later.

When no delivery succeeds for 8 hours (the server was down or unreachable), Max drops the
subscription, and updates wait until the bot subscribes again. `bot.ensureWebhook(url, secret)`
checks the subscription and subscribes again if it is gone: call it periodically (the Spring Boot
starter does it for you). Subscribing to the same URL again is harmless. A renewal is logged and
reported to `MaxEventListener.onWebhookResubscribe`.

To go back to long polling, remove the subscription with `bot.deleteWebhook()` or
`bot.client.unsubscribe(url)`.

## API client

### Calling methods

```kotlin
val client = MaxClient(token)

val me = client.getMyInfo()
val chat = client.getChat(chatId)
val sent = client.sendMessageToChat(chatId, "Hello")
client.editMessage(sent.body!!.mid, NewMessageBody(text = "Hello, edited"))
client.pinMessage(chatId, sent.body!!.mid, notify = false)

client.close()
```

All 33 operations, by area (Max no longer serves `addMembers`, see below):

| Area          | Methods                                                                                                   |
|---------------|-----------------------------------------------------------------------------------------------------------|
| Bot           | `getMyInfo`, `editMyCommands`                                                                             |
| Chats         | `getChat`, `editChat`, `sendAction`, `getPinnedMessage`, `pinMessage`, `unpinMessage`, `leaveChat`        |
| Members       | `getMembership`, `getMembers`, ~~`addMembers`~~, `removeMember`, `getAdmins`, `postAdmins`, `deleteAdmins` |
| Messages      | `getMessages`, `getMessageById`, `sendMessage`, `editMessage`, `deleteMessage`, `answerOnCallback`, `getVideoAttachmentDetails` |
| Comments      | `getComments`, `getCommentById`, `sendComment`, `editComment`, `deleteComment`                            |
| Updates       | `getUpdates`, `getSubscriptions`, `subscribe`, `unsubscribe`                                              |
| Uploads       | `getUploadUrl`                                                                                            |

On top of them there are convenience methods: `sendMessageToChat`, `sendMessageToUser`,
`sendLongMessage*` (see [Long text](#long-text)), `answerOnCallback(callbackId, notification)`,
`upload`, `uploadImage`, `uploadVideo`, `uploadAudio` and `uploadFile`.

`addMembers` (`POST /chats/{chatId}/members`) is `@Deprecated`: Max restricted the method on
2026-09-09 and removed it on 2026-09-30, so the server rejects it. It is kept for compatibility (the
specification still lists it); there is no replacement.

Optional parameters are Kotlin default arguments:

```kotlin
client.getMessages(chatId = chatId, count = 10)
client.removeMember(chatId, userId, block = true)
```

### Using the client from Java

```java
MaxClient client = MaxClient.create(token);

// Blocking: every method waits for the response.
MaxBlockingClient api = client.blocking();
BotInfo me = api.getMyInfo();
api.sendMessageToChat(chatId, "Hello");
List<Message> last = api.getMessages(chatId, null, null, null, 10);

// Asynchronous: every method returns a CompletableFuture.
client.async().getChat(chatId)
        .thenAccept(chat -> System.out.println(chat.getTitle()))
        .exceptionally(error -> { error.printStackTrace(); return null; });

client.close();
```

Both facades have exactly the same methods as `MaxClient`. Optional parameters are available as
overloads (`getMembers(chatId)`, `getMembers(chatId, userIds)`, ...). Cancelling a future cancels
the HTTP request. Do not use the blocking facade on event loop threads (Netty, Vert.x, Android main thread).

### Building messages

`NewMessageBody` is the content of an outgoing or edited message. Build it in one of three ways:

```kotlin
// Kotlin DSL
val body = message {
    markdown("**Order #42** is ready")
    replyTo(originalMid)          // or forward(mid)
    notify(false)                 // deliver silently
    attach(photo)                 // any AttachmentRequest
    keyboard { row { callback("Pick up", "pickup:42") } }
}

// Constructor
val plain = NewMessageBody(text = "Hello", format = TextFormat.HTML)
```

```java
// Java builder
NewMessageBody body = NewMessageBody.builder()
        .html("<b>Order #42</b> is ready")
        .replyTo(originalMid)
        .keyboardRow(Buttons.callback("Pick up", "pickup:42"))
        .build();
```

Attachments that are not uploads:

```kotlin
PhotoAttachmentRequest.ofUrl("https://example.com/cat.png")  // the server downloads the image
PhotoAttachmentRequest.ofToken(token)                         // re-send an image that is already in Max
StickerAttachmentRequest.ofCode(code)
LocationAttachmentRequest(latitude = 55.75, longitude = 37.62)
ShareAttachmentRequest.ofUrl("https://example.com")           // link preview
ContactAttachmentRequest(ContactAttachmentRequestPayload(name = "Support", vcfPhone = "+70000000000"))
```

When editing a message, `attachments = null` keeps the current attachments and an empty list removes
them all. In the builder, `clearAttachments()` sends the empty list.

`sendMessage` rejects a message without text, attachments or a forward at once with
`IllegalArgumentException`: the server does not accept such messages anyway.

Everything that breaks the limits of Max (constants in `MaxLimits`) is rejected the same way,
without a request. `sendMessage`, `editMessage`, `answerOnCallback` (the new message content),
`sendComment`, `editComment`, `editMyCommands` and `MaxBot.syncCommands` check:

| What                                  | Limit                                                          |
|---------------------------------------|----------------------------------------------------------------|
| text of a message or a comment        | up to 4000 characters                                          |
| keyboard                              | up to 30 rows, 1 to 210 buttons, up to 7 buttons in a row      |
| a row with a `link`, `open_app`, `request_geo_location` or `request_contact` button | up to 3 buttons  |
| button text                           | 1–128 characters                                               |
| payload of `callback` and `clipboard` buttons | up to 1024 characters                                  |
| `url` of a `link` button              | up to 2048 characters                                          |
| payload of an `open_app` button       | up to 512 characters, only `A-Z`, `a-z`, `0-9`, `_` and `-`    |
| bot commands                          | up to 32; name of 1–64 characters, description of 1–128        |

Lengths are counted like `String.length`, in UTF-16 code units, because the server counts them that
way: the 😀 emoji takes 2 of the 4000 characters, a flag 4, and Markdown and HTML markup counts too.

### Long text

A message holds 4000 characters. `sendLongMessage` (and `sendLongMessageToChat`,
`sendLongMessageToUser`, `replyLong` in handlers) sends longer text as several messages, and shorter
text as one, like `sendMessage`:

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

- Text is cut between paragraphs, else between lines, else between words; a weaker break is used
  only when the stronger one would leave the part less than half full. A word longer than a message
  is cut where it must, but never inside a surrogate pair (an emoji), before a combining mark or
  next to a zero-width joiner.
- Markup stays valid in every part. In Markdown, links and escapes like `\*` are never cut, while
  `**bold**`, `` `code` `` and other spans that cross a cut are closed at the end of one part and
  opened again at the start of the next; a quote `> ` or a heading `# ` goes on in the next part. In
  HTML, tags and entities are never cut, and open tags are closed and opened again with their
  attributes.
- The keyboard and other attachments go with the last message, the reply or forward (`link`) with
  the first; every part has the same `notify` and format. If a part fails, the parts sent before it
  stay sent.

`TextSplitter.split(text, format)` returns the parts without sending them, e.g. to send the text as
comments.

### Formatting

Set the format to `MARKDOWN` (`markdown(...)` in the builder) or `HTML` (`html(...)`):

| Style         | Markdown                  | HTML                         |
|---------------|---------------------------|------------------------------|
| Bold          | `**text**` or `__text__`  | `<b>`, `<strong>`            |
| Italic        | `*text*` or `_text_`      | `<i>`, `<em>`                |
| Strikethrough | `~~text~~`                | `<s>`, `<del>`               |
| Underline     | `++text++`                | `<u>`, `<ins>`               |
| Monospace     | `` `code` ``              | `<code>`, `<pre>`            |
| Highlight     | `^^text^^`                | `<mark>`                     |
| Link          | `[text](https://...)`     | `<a href="https://...">`     |
| User mention  | `[Name](max://user/123)`  | —                            |
| Quote         | `> text`                  | `<blockquote>`               |
| Header        | `# text`                  | `<h1>`                       |

In received messages formatting comes as `MessageBody.markup`, a list of `MarkupElement` ranges.

### Uploading files

`upload*` methods perform the whole three-step upload (get an upload URL, upload the file, build the
attachment) and return an `AttachmentRequest` ready to send:

```kotlin
val photo = client.uploadImage(UploadSource.of(File("cat.png")))
val video = client.uploadVideo(UploadSource.of(Path.of("movie.mp4")))
val file = client.uploadFile(UploadSource.of(bytes, "report.pdf"))
val audio = client.uploadAudio(UploadSource.of(inputStream, "voice.mp3"))

client.sendMessageToChat(chatId, message { text("Here is the report"); attach(file) })
```

`UploadSource` accepts a `File`, a `Path`, a `ByteArray` or an `InputStream` (read once). The content
type is guessed from the file name.

Limits of a single upload:

| Type    | Formats                               | Limit                          |
|---------|---------------------------------------|--------------------------------|
| `IMAGE` | JPG, JPEG, PNG, GIF, TIFF, BMP, HEIC  | 50 MB and 7680×7680 px         |
| `VIDEO` | MP4, MOV, MKV, WEBM                   | 250 MB                         |
| `AUDIO` | MP3, WAV, M4A and others              | 256 MB and 60 minutes          |
| `FILE`  | any common format                     | 4 GB                           |

Audio, file, sticker and contact attachments must be the only attachment of a message.

Large media may still be processing on the server when you send the message. The server then answers
`attachment.not.ready`, and `sendMessage`, `editMessage` and `answerOnCallback` re-send the request
automatically (5 times, 1 second apart by default; see [Configuration](#configuration)).

The upload URL belongs to a different host, so the library never sends the bot token there.

### Pagination

Methods that return pages give you a `marker` to pass to the next call:

```kotlin
var marker: Long? = null
do {
    val page = client.getMembers(chatId, marker = marker, count = 100)
    page.members.forEach { println("${it.firstName} admin=${it.isAdmin}") }
    marker = page.marker
} while (marker != null)
```

`getMessages` and `getComments` page by time instead: pass `before` / `after` (Unix milliseconds).

## Spring Boot

`maxapi-spring-boot-starter` creates a `MaxClient` and a `MaxBot` from `max.bot.*` properties and
starts and stops the bot with the application. It is built and tested with Spring Boot 4.1 (Java 17+).

```kotlin
dependencies {
    implementation("io.github.securitytrip:maxapi-spring-boot-starter:<version>")
    implementation("org.springframework.boot:spring-boot-starter-web") // for webhook mode only
}
```

```yaml
max:
  bot:
    token: ${MAX_BOT_TOKEN}
    mode: polling            # polling (default), webhook or none
    sync-commands: true      # show commands with descriptions in the bot menu on startup
    trust-russian-root-ca: true  # default; see "TLS certificate" below
    storage: memory          # memory (default), jdbc or redis; see "Storage"
    webhook:
      path: /max/webhook     # the endpoint in your Spring MVC server
      url: https://bot.example.com/max/webhook   # subscribe on startup (optional)
      secret: ${MAX_WEBHOOK_SECRET}
      async: false           # true: respond before the handler finishes
```

Handlers come from beans: every `MaxBotConfigurer` is called with the bot, and every `Router` bean
is included, both in `@Order` order.

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

| Mode      | What happens                                                                              |
|-----------|-------------------------------------------------------------------------------------------|
| `polling` | long polling starts with the context; on shutdown the bot stops polling and finishes the updates already received |
| `webhook` | `POST max.bot.webhook.path` accepts deliveries (servlet web applications); with `webhook.url` set the bot subscribes on startup |
| `none`    | nothing starts; deliver updates yourself                                                  |

`MaxEventListener` beans (and `MaxMetrics`, when `maxapi-micrometer` and a `MeterRegistry` are
present) receive the events of the client and the bot. `StateStorage`, `MarkerStore` and
`UpdateDeduplicator` beans are used by the bot, see [Storage](#storage).

The webhook endpoint answers 200 for accepted updates, 401 for a wrong secret, 400 for a body that
is not an update and 503 when the bot is closed. Define your own `MaxClient`, `MaxBot` or
`BotConfig` bean to replace the default one, and `MaxClientConfigCustomizer` beans to adjust the
client settings (retry policy, `OkHttpClient`).

The `@MaxWebAppUser` argument for controllers of a mini app backend is configured with the
`max.bot.web-app.*` properties, see [Spring MVC backend](#spring-mvc-backend).

A complete application is in [examples-spring](examples-spring).

## Ktor

`maxapi-ktor` adds `maxWebhook` to Ktor routing:

```kotlin
val bot = MaxBot(token)
bot.onCommand("start") { reply("Hello from Ktor!") }
bot.setWebhook("https://bot.example.com/max/webhook", secret)

embeddedServer(Netty, port = 8080) {
    routing { maxWebhook("/max/webhook", bot, secret) }
    monitor.subscribe(ApplicationStopping) { runBlocking { bot.client.unsubscribe("https://bot.example.com/max/webhook") } }
}.start(wait = true)
```

Responses are the same as in the Spring starter. `async = true` responds before the handler
finishes. Remove the subscription when the server stops, as above: while it exists, long polling
receives no updates. See [examples-ktor](examples-ktor). The `MaxWebApp` plugin for a mini app
backend is described in [Ktor backend](#ktor-backend).

## Mini apps

A mini app is a web page that Max opens inside the chat with a bot. The page gets its launch data in
`window.WebApp.initData` and passes it to its backend; the backend checks the signature and only then
trusts the user. `initDataUnsafe` on the page is not signed and must not be relied on. Everything the
backend needs is in the `ru.securitytrip.maxapi.webapp` package of the `maxapi` module, with no new
dependencies.

Max has three different signatures, which must not be confused:

| What is checked                           | HMAC-SHA256 key                       | Signed data                                                    | Method                     |
|-------------------------------------------|---------------------------------------|----------------------------------------------------------------|----------------------------|
| `initData` of a mini app                  | `HMAC_SHA256("WebAppData", botToken)` | fields except `hash`, sorted by key, `key=value` joined by `\n` | `WebAppData.validate`      |
| phone number from `requestContact()`      | `botToken`                            | `authDate=…\nphone=<without +>\nuserId=…`                      | `WebAppPhone.verify`       |
| `contact` attachment of `request_contact` | `botToken`                            | `vcf_info` exactly as received (with CRLF)                     | `ContactAttachment.verify` |

Hashes are compared in constant time.

### Validating initData

```kotlin
val data = WebAppData.validate(initData, botToken)                 // maxAge is 1 hour by default
val user = data.user!!                                              // userId, firstName, username, languageCode, photoUrl
println("${user.firstName} opened the app from chat ${data.chat?.id}, start_param=${data.startParam}")
```

```java
try {
    WebAppData data = WebAppData.validate(initData, botToken, Duration.ofMinutes(30));
} catch (WebAppDataException e) {
    // e.getReason(): MALFORMED, MISSING_HASH, DUPLICATE_HASH, INVALID_SIGNATURE or EXPIRED
}
```

`validate` checks the data [as the Max documentation describes](https://dev.max.ru/docs/webapps/validation):
it parses the string URL-decoding the values once, requires exactly one `hash`, checks the signature
and rejects data whose `auth_date` is older than `maxAge`. The result has the user, the chat (`id`
and `ChatType`), `queryId`, `authDate`, `startParam`, `ip` and `fields`: all fields, including those
the class has no property for. `WebAppDataException` (unchecked, a `MaxException`) names the reason
and never contains the token.

`WebAppData.verifySignature(initData, botToken)` only checks the signature: it neither parses the
fields nor looks at `auth_date`, and returns `false` instead of throwing. Use `validate` for
authorization: old data could have been intercepted.

### Phone number from requestContact

`window.WebApp.requestContact()` returns `{ phone, authDate, hash }`. The page sends this object to
the backend together with `initData`, and the backend checks it for the user from `initData`:

```kotlin
val user = WebAppData.validate(initData, botToken).user!!
val phone = WebAppPhone(body.phone, body.authDate, body.hash)
if (!phone.verify(user.userId, botToken)) throw Unauthorized()
```

A leading `+` in the number does no harm: it is dropped before the check, as the formula requires.
The method does not check how old `authDate` is.

### Contact from a request_contact button

When the user presses a `request_contact` button, the bot gets a message with a `ContactAttachment`,
and `verify` checks that Max signed this contact for your bot:

```kotlin
bot.onMessage({ it.message.body?.attachments.orEmpty().any { a -> a is ContactAttachment } }) {
    val contact = message.body!!.attachments!!.filterIsInstance<ContactAttachment>().first()
    reply(if (contact.verify(token)) "Phone number verified" else "Contact not verified")
}
```

Only a contact shared with the button is signed; a forwarded or hand-made contact card fails the
check, so nobody can pass off someone else's number as their own.

### Link builders

```kotlin
MaxLinks.bot("shop_bot")                          // https://max.ru/shop_bot
MaxLinks.botStart("shop_bot", "ref-42")           // https://max.ru/shop_bot?start=ref-42
MaxLinks.miniApp("shop_bot")                      // https://max.ru/shop_bot?startapp
MaxLinks.miniApp("shop_bot", "promo_summer")      // https://max.ru/shop_bot?startapp=promo_summer
MaxLinks.share("Look: https://example.com")       // https://max.ru/:share?text=Look%3A%20https%3A%2F%2Fexample.com
```

Max silently drops a payload that breaks its limits, so the builders throw `IllegalArgumentException`
instead: `start` is 1 to 128 characters (everything except letters, digits and `_-.~` is
percent-encoded), `startapp` is up to 512 characters of `A-Za-z0-9_-` only. The same rules for the
payload of an `open_app` button are checked by `MaxLimits.isValidOpenAppPayload` and
`MaxLimits.checkOpenAppPayload`, and `Buttons.openApp` checks the payload when the button is created.
The mini app gets the `startapp` value in `WebAppData.startParam`.

### Spring MVC backend

With `max.bot.token` set, the starter adds the `@MaxWebAppUser` argument to Spring MVC. The parameter
may be a `WebAppUser` or a `WebAppData`; a request without `initData` or with invalid data gets 401
before the method is called.

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
      source: header           # header (default), parameter (query or form) or body
      header: X-Max-Init-Data  # header for source=header
      parameter: initData      # parameter for source=parameter
      max-age: 1h              # how old auth_date may be
```

On the page: `fetch('/api/me', { headers: { 'X-Max-Init-Data': window.WebApp.initData } })`.
`@MaxWebAppUser(required = false)` passes `null` when the request has no `initData`. With
`source: body` the whole request body is `initData`, and the controller cannot read the body again.
A backend with a mini app page is in [examples-spring](examples-spring) (`MiniAppBackend.kt` and
`static/index.html`).

### Ktor backend

```kotlin
routing {
    route("/api") {
        install(MaxWebApp) {
            botToken = System.getenv("MAX_BOT_TOKEN")
            maxAge = Duration.ofMinutes(30)       // 1 hour by default
            // header("X-Init") or initData { it.request.queryParameters["initData"] }
        }
        get("/me") { call.respondText("Hello, ${call.maxWebAppUser().firstName}") }
        get("/start") { call.respondText(call.maxInitData().startParam.orEmpty()) }
    }
}
```

The plugin checks `initData` from the `X-Max-Init-Data` header on every call of the routes it is
installed in, and answers 401 when the data is missing, invalid or too old, or has no user
(`requireUser = false` lets data without a user through).

## Testing bots

`maxapi-test` runs a bot without a network: `BotTester` feeds it updates and records the API calls
its handlers make.

```kotlin
@Test
fun `start shows the menu`(): Unit = runBlocking {
    BotTester().use { tester ->
        registerHandlers(tester.bot)                 // your code that sets up the bot

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

- `sendMessage`, `pressButton`, `startBot` and `send(update)` run the handler and return when it
  completes; an exception in the handler is rethrown, so the test fails.
- `sentMessages`, `callbackAnswers` and `calls` (every request, with method, path, query and body)
  show what the bot did.
- Calls are answered with defaults: `sendMessage` returns the message, `getMyInfo` returns a bot
  named `test_bot`, other methods return `{"success":true}`. Use
  `stub("GET", "/chats/42", json)` for calls whose result the handler reads, or with a status like
  404 to test error handling.
- `TestUpdates` builds other updates: `TestUpdates.message("hi", chatType = ChatType.CHAT)`.

## Models

Models are split into packages by area under `ru.securitytrip.maxapi.model`:

| Package                     | Contents                                                                         |
|-----------------------------|----------------------------------------------------------------------------------|
| `model`                     | shared types: `ApiEnum`, `SimpleQueryResult`, `ErrorResponse`                    |
| `model.user`                | `User`, `UserWithPhoto`, `BotInfo`, `BotCommand`                                 |
| `model.chat`                | `Chat`, `ChatPatch`, `ChatMember`, `ChatAdmin`, `ChatAdminPermission`, `SenderAction` |
| `model.message`             | `Message`, `MessageBody`, `NewMessageBody`, `Callback`, `CallbackAnswer`, comments |
| `model.attachment`          | received `Attachment`s, outgoing `AttachmentRequest`s, `UploadType`              |
| `model.keyboard`            | `Keyboard` and `Button`s                                                         |
| `model.markup`              | `TextFormat` and received `MarkupElement`s                                       |
| `model.update`              | `Update` and its subclasses, `UpdateType`, `UpdateList`                          |
| `model.subscription`        | webhook `Subscription`s                                                          |

Each file holds one main class together with its small payload classes, e.g. `PhotoAttachment.kt`
contains `PhotoAttachment` and `PhotoAttachmentPayload`.

Models are immutable Kotlin data classes, so Java code sees getters (`message.getBody().getText()`),
and `equals`, `hashCode`, `toString` and `copy` work as expected.

Polymorphic families are sealed classes:

| Family              | Discriminator  | Examples                                                    | Fallback            |
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

Every `Update` exposes `chatId` and `user` regardless of its type.

Identifiers: users and chats are `Long` (group chats and channels have negative ids); messages and
comments are `String` (`MessageBody.mid`). Times are Unix milliseconds in UTC.

If you need to parse or serialize models yourself, use `MaxJson.createMapper()` (snake_case, `null`
fields skipped) and `MaxJson.parseUpdate(json)`.

## Errors and retries

All exceptions are unchecked and extend `MaxException`:

| Exception                    | When                                                                  |
|------------------------------|-----------------------------------------------------------------------|
| `UnauthorizedException`      | 401: the token is missing or invalid                                  |
| `ForbiddenException`         | 403: no access, e.g. the user stopped the bot                         |
| `NotFoundException`          | 404: the chat or message does not exist or is not accessible          |
| `TooManyRequestsException`   | 429: rate limit exceeded after all retries; has `retryAfter`          |
| `MaxApiException`            | any other HTTP error; base class of the four above                    |
| `MaxNetworkException`        | the server could not be reached; the `IOException` is the cause       |
| `MaxException`               | the response could not be parsed, or another library-level failure    |

`MaxApiException` carries `httpStatus`, `errorCode` (e.g. `chat.denied`), `errorMessage` and the raw `responseBody`:

```kotlin
try {
    client.sendMessageToUser(userId, "Reminder")
} catch (e: ForbiddenException) {
    markUserInactive(userId) // the user stopped the bot
} catch (e: MaxApiException) {
    log.warn("Max API error ${e.httpStatus} ${e.errorCode}: ${e.errorMessage}")
}
```

Retries (`RetryPolicy`, 3 retries starting at 500 ms and doubling, by default):

- HTTP 429 and 503 mean the request was rejected, so they are retried for every request.
- HTTP 502, 504 and network errors are retried only for `GET`, `PUT` and `DELETE`. `POST` and `PATCH`
  are not retried then, because the server may already have processed them (e.g. sent the message).
- `Retry-After` (seconds or an HTTP date) is respected. If it is longer than `maxDelay`, the request
  fails at once with `TooManyRequestsException` instead of retrying too early.
- Other errors are thrown immediately.

## Configuration

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

| Setting                   | Default                         | Description                                                  |
|---------------------------|---------------------------------|--------------------------------------------------------------|
| `token`                   | required                        | bot access token                                             |
| `baseUrl`                 | `https://platform-api2.max.ru`  | API endpoint; change only for tests or proxies               |
| `httpClient`              | created by the library          | your own `OkHttpClient` (proxy, logging, timeouts)           |
| `retryPolicy`             | `RetryPolicy.DEFAULT`           | see [Errors and retries](#errors-and-retries); `RetryPolicy.NONE` disables retries |
| `attachmentReadyRetries`  | `5`                             | re-sends while the server answers `attachment.not.ready`     |
| `attachmentReadyDelay`    | 1 second                        | pause between those re-sends                                 |
| `userAgent`               | `maxapi-jvm`                    | `User-Agent` header                                          |
| `rateLimit`               | none                            | client-side request rate limit, see [Reliability](#reliability-and-monitoring) |
| `eventListener`           | none                            | receives an event per HTTP attempt (metrics, tracing)        |
| `trustRussianRootCa`      | `true`                          | the library's own HTTP client trusts the Russian Trusted Root CA, see [TLS certificate](#tls-certificate) |

The default HTTP client uses 15 s connect and 60 s read/write timeouts. `getUpdates` automatically
gets a longer read timeout, so long polling works with your own client too. A client you pass in is
never closed by the library.

### TLS certificate

The certificate of `platform-api2.max.ru` is issued under the Russian Trusted Root CA of the Ministry
of Digital Development. JDKs do not include that CA, so a plain `OkHttpClient` fails with
`PKIX path building failed`. The library bundles the root certificate, and the HTTP client it creates
trusts it in addition to the certificates the JVM trusts. Only that client is affected: the trust of
the rest of the JVM stays as it is, and there is no need for `-Djavax.net.ssl.trustStore` or for
importing the certificate into `cacerts`.

A client you pass in `httpClient` is used as is. Add the certificate to it with `MaxTls`:

```kotlin
val http = MaxTls.trustRussianRootCa(OkHttpClient.Builder())
    .proxy(proxy)
    .build()
val client = MaxClient(MaxClientConfig(token, httpClient = http))
```

`MaxTls.sslContext` and `MaxTls.trustManager` do the same for other HTTP clients, and
`MaxTls.russianTrustedRootCa` is the certificate itself. If the JVM already trusts the CA, turn the
bundled one off with `trustRussianRootCa = false` (`max.bot.trust-russian-root-ca=false` in Spring Boot).

`BotConfig`:

| Setting               | Default   | Description                                                      |
|-----------------------|-----------|------------------------------------------------------------------|
| `parallelism`         | `4`       | workers handling updates; one chat is always handled in order    |
| `queueCapacity`       | `64`      | queue size per worker; polling waits when it is full             |
| `pollTimeoutSeconds`  | `30`      | how long one `getUpdates` request waits, 0–90                    |
| `pollLimit`           | `100`     | maximum updates per request, 1–1000                              |
| `minPollInterval`     | 300 ms    | minimum interval between requests (the API requires ≥ 300 ms)    |
| `maxErrorBackoff`     | 30 s      | maximum pause after polling errors                               |
| `stateStorage`        | in memory | where [dialog states](#dialog-state) are kept                    |
| `handlerTimeout`      | none      | longest time a handler may run                                   |
| `deduplicator`        | none      | drops repeated deliveries of an update                           |
| `markerStore`         | none      | keeps the polling marker across restarts                         |
| `eventListener`       | none      | receives an event per dispatched update                          |

Logging uses `System.Logger` with the names `ru.securitytrip.maxapi.client` (retries, at `DEBUG`) and
`ru.securitytrip.maxapi.bot` (handler errors, at `ERROR`).

## Reliability and monitoring

All of the following is off by default.

**Rate limit.** `RateLimit` makes the client wait instead of getting 429 responses: a token bucket
for all requests and, optionally, one per chat.

```kotlin
MaxClientConfig(token, rateLimit = RateLimit(requestsPerSecond = 25.0, perChatRequestsPerSecond = 1.0))
```

**Polling marker.** With a `MarkerStore` the bot continues polling where it stopped after a restart.
The marker is saved only after all updates received before it were handled, so nothing is lost on a
crash, but an update may be handled twice (at-least-once delivery). `FileMarkerStore(path)` keeps it
in a file; stores in a database and in Redis are described in [Storage](#storage), and a store of
your own implements `MarkerStore` (or extends `BlockingMarkerStore` in Java).

**Duplicates.** Max redelivers a webhook update until it gets 200, for up to 8 hours.
`InMemoryUpdateDeduplicator` drops updates seen recently, by message id, callback id or, for other
updates, type, time, chat and user (`UpdateDeduplicator.keyOf`). With several instances, use the
shared stores of [Storage](#storage).

**Handler timeout.** `handlerTimeout` cancels a handler that runs too long (Java handlers are
interrupted) and passes `HandlerTimeoutException` to `onError`, so a stuck handler does not block its chat.

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

**Metrics.** `MaxEventListener` receives an event for every HTTP attempt (`RequestEvent`: method,
path template like `/chats/{id}`, status, duration, attempt) and every dispatched update
(`UpdateEvent`: update, handled, duration, error). `maxapi-micrometer` turns them into the timers
`max.client.requests` and `max.bot.updates`:

```kotlin
val metrics = MaxMetrics(meterRegistry)
val client = MaxClient(MaxClientConfig(token, eventListener = metrics))
val bot = MaxBot(client, BotConfig(eventListener = metrics))
```

With the Spring Boot starter, adding `maxapi-micrometer` is enough when the application has a
`MeterRegistry` (e.g. from Actuator); other `MaxEventListener` beans are attached as well.

## Storage

By default dialog states live in memory: they are lost on restart, and instances of a bot do not
see each other's states. `maxapi-jdbc` and `maxapi-redis` keep the dialog states, the polling marker
and the keys of handled updates in an SQL database or in Redis, so they survive restarts, are shared
by all instances, and a repeated delivery of an update is dropped even if another instance got the
first one.

| What                       | JDBC (any database)        | Redis (Lettuce)            |
|----------------------------|----------------------------|----------------------------|
| dialog states              | `JdbcStateStorage`         | `RedisStateStorage`        |
| polling marker             | `JdbcMarkerStore`          | `RedisMarkerStore`         |
| duplicate updates (9 h)    | `JdbcUpdateDeduplicator`   | `RedisUpdateDeduplicator`  |

```kotlin
// JDBC: any DataSource; createTable() creates a missing table with standard SQL
val bot = MaxBot(
    token,
    BotConfig(
        stateStorage = JdbcStateStorage(dataSource).createTable(),
        markerStore = JdbcMarkerStore(dataSource).createTable(),
        deduplicator = JdbcUpdateDeduplicator(dataSource).createTable(),
    ),
)

// Redis: one connection is enough for all three
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

The JDBC stores use only standard SQL and are tested on H2 and PostgreSQL. Tables are named
`max_dialog_state`, `max_bot_marker` and `max_seen_update` by default; their `CREATE TABLE`
statements are in the KDoc of each class, for migrations. Redis keys start with `max:`. Pass other
table names, key prefixes or `botId`s when several bots share one database.
`JdbcStateStorage.deleteOlderThan(age)` and the `ttl` of `RedisStateStorage` remove abandoned dialogs.

With the Spring Boot starter, add the module and choose it with `max.bot.storage`:

```yaml
max:
  bot:
    storage: jdbc              # memory (default), jdbc or redis
    jdbc:
      table-prefix: max_       # max_dialog_state, max_bot_marker, max_seen_update
      create-tables: true      # false when migrations create the tables
    redis:
      url: redis://localhost:6379
      key-prefix: "max:"
      state-ttl: 7d            # by default states are kept until cleared
```

`jdbc` uses the application's `DataSource`. Your own `StateStorage`, `MarkerStore` or
`UpdateDeduplicator` bean replaces the matching store, also without `max.bot.storage`.

## Threading and lifecycle

- `MaxClient` is thread-safe. Create one per token and share it. Call `close()` on shutdown to
  release the HTTP client and cancel pending `async()` calls.
- `MaxBot(token)` creates and owns its client; `MaxBot(client)` uses yours. `MaxBot.close()` stops
  polling, cancels running handlers and closes the client only if the bot owns it.
- Kotlin handlers run in the coroutine context of the delivery: `Dispatchers.Default` with `start()`,
  the caller's context with `startPolling()`, `processUpdate()` and `WebhookHandler.handle()`. For example,
  `runBlocking { bot.startPolling() }` runs all handlers on that one thread, interleaving them at
  suspension points. Do not block in Kotlin handlers; use `withContext(Dispatchers.IO)` for blocking work.
- Java handlers always run on `Dispatchers.IO` and may block.
- `*Blocking` methods use `runBlocking`. Call them from Java handlers or plain threads, not from coroutines.

## Project layout

```
maxapi/                        the library
  src/main/kotlin/ru/securitytrip/maxapi/
    model/    API models written from the specification, one subpackage per area:
              user, chat, message, attachment, keyboard, markup, update, subscription
    client/   MaxClient, configuration, exceptions, JSON, uploads, HTTP transport
    java/     MaxBlockingClient and MaxAsyncClient facades
    dsl/      message and keyboard builders, Buttons
    bot/      MaxBot, routers, filters, contexts, dialog state, long polling, WebhookHandler
    webapp/   mini apps: validation of initData, phone numbers and contacts; deep links
  src/test/   tests (Kotlin and Java) against a mock HTTP server
  api/        public API dump checked by the binary compatibility validator
  spec/       Max Bot API specification the library is checked against
  docs/       module and package descriptions for the API reference
maxapi-spring-boot-starter/    Spring Boot auto-configuration
maxapi-ktor/                   Ktor webhook routing and mini app plugin
maxapi-test/                   BotTester and TestUpdates
maxapi-micrometer/             MaxMetrics
maxapi-jdbc/                   JDBC stores (tests on H2 and PostgreSQL)
maxapi-redis/                  Redis stores (tests on Redis)
maxapi-bom/                    bill of materials
build-logic/                   convention plugins: Kotlin settings, coverage, Dokka, publishing
examples/, examples-spring/, examples-ktor/
integration-tests/             a whole Spring Boot application: fake Max, PostgreSQL and Redis
```

The `client` and `model` packages do not depend on `bot`, so the client can be used on its own.

## Building and testing

```bash
./gradlew build
```

This compiles all modules and the examples and runs the tests. The tests use OkHttp's
`MockWebServer`, Spring's context runners and Ktor's test host, and the tests of the stores and the
starter start PostgreSQL and Redis with Testcontainers, so they need Docker. No token or access to
the API is needed. Every API method is checked through all three facades (Kotlin, blocking, async),
as is every update, attachment, button and markup type.

The `integration-tests` module runs a whole Spring Boot application against a fake Max server: long
polling restarted in the middle of a dialog on PostgreSQL and on Redis (the dialog continues, polling
resumes from the saved marker, a repeated delivery is dropped), and a webhook on a real Tomcat with
the secret check and deduplication. Run them alone with `./gradlew :integration-tests:test`.

Every library module has 100% line coverage, and `./gradlew check` (part of `build`) fails if a
change lowers it, or if branch coverage drops below 95% (Kotlin generates a few null-check branches
that cannot be reached). Coverage reports (Kover) are written to
`<module>/build/reports/kover/html/index.html`:

```bash
./gradlew koverHtmlReport
```

The public API of each module is recorded in `<module>/api/<module>.api`, and `./gradlew check`
fails when it changes. After an intended change, update the dumps and commit them together with the
code, so the change is visible in review:

```bash
./gradlew apiDump
```

The library is checked against the API specification in [maxapi/spec/schema.yaml](maxapi/spec/schema.yaml):
`SpecConformanceTest` compares its operations, update, attachment, button and markup types and enums
with the client and the models. To support a new version of Max Bot API, replace that file and run the
tests: the failures list what has to be added.

When writing tests as `fun name() = runBlocking { ... }`, declare the return type explicitly:
`fun name(): Unit = runBlocking { ... }`. Otherwise a test whose last expression returns a value
(e.g. `assertIs<T>(...)`) gets a non-`Unit` return type, and JUnit silently skips it.

API reference is generated with [Dokka](https://github.com/Kotlin/dokka) from the KDoc comments and
the package descriptions in [maxapi/docs/module.md](maxapi/docs/module.md):

```bash
./gradlew :dokkaGenerate
```

The reference of all modules is in `build/dokka/html/index.html`. Each module's `-javadoc.jar`
contains its own part, and every stable release publishes the whole reference to
[securitytrip.github.io/maxapi](https://securitytrip.github.io/maxapi/).

Run the examples against a real bot:

```bash
MAX_BOT_TOKEN=your-token ./gradlew :examples:run
```

```bash
MAX_BOT_TOKEN=your-token ./gradlew :examples:run -PmainClass=ru.securitytrip.maxapi.examples.JavaEchoBot
```

## Releasing

Releases are published to Maven Central by the [Release workflow](.github/workflows/release.yml)
when a GitHub release is published. Every push and pull request is checked by the
[CI workflow](.github/workflows/ci.yml).

### One-time setup

1. Sign in to [central.sonatype.com](https://central.sonatype.com/) with the `SecurityTrip` GitHub
   account. The `io.github.securitytrip` namespace is verified automatically for it; check that it is
   listed under **Namespaces**.
2. Generate a user token in **Account → Generate User Token**. It consists of a username and a password.
3. Create a GPG key for signing and publish its public part, so that Maven Central can verify
   signatures:

   ```bash
   gpg --quick-gen-key "SecurityTrip <your-email>" rsa4096 sign 3y
   gpg --list-secret-keys --keyid-format=long          # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <key-id>
   gpg --armor --export-secret-keys <key-id>           # value for GPG_SIGNING_KEY
   ```

4. Add repository secrets in **Settings → Secrets and variables → Actions**:

   | Secret                       | Value                                          |
   |------------------------------|------------------------------------------------|
   | `MAVEN_CENTRAL_USERNAME`     | username of the user token                     |
   | `MAVEN_CENTRAL_PASSWORD`     | password of the user token                     |
   | `GPG_SIGNING_KEY`            | the armored secret key, including BEGIN/END    |
   | `GPG_SIGNING_KEY_PASSWORD`   | the key passphrase (omit if it has none)       |

5. Enable GitHub Pages for the API reference: **Settings → Pages → Build and deployment → Source:
   GitHub Actions**. The Release workflow deploys from the release tag, so allow tags too:
   **Settings → Environments → github-pages → Deployment branches and tags → Add deployment
   branch or tag rule**, type **Tag**, pattern `v*`. Without it the `docs` job fails with
   "not allowed to deploy to github-pages due to environment protection rules".

### Publishing a version

1. Move the entries under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) to a new
   `## [1.2.3] - YYYY-MM-DD` section, update the links at the bottom, and merge that to `master`.
   The workflow refuses to publish a version without its section.
2. Create a GitHub release with the tag `v1.2.3` (or `v1.2.3-rc1`, marked as a pre-release).

The workflow takes the version from the tag, builds and tests it, signs the artifacts of all modules
(the BOM included) and publishes
them in one deployment; Maven Central releases them after validation, and they usually appear in search within an hour.
It then replaces the release notes with the changelog section and, for stable releases, publishes the
API reference to GitHub Pages.

Maven Central has a free monthly limit on the number of releases and files per organization; the
current usage is shown in the Usage Center on central.sonatype.com. So check release candidates
(`-rc`) locally instead of publishing them.

If publishing fails after the release was created, fix the cause and run the Release workflow by
hand (**Actions → Release → Run workflow**) with the same version. A version that reached Maven Central
cannot be replaced: publish a new one instead.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright 2026 SecurityTrip

Licensed under the [Apache License, Version 2.0](LICENSE).
