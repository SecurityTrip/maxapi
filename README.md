# maxapi

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
- [Getting a token](#getting-a-token)
- [Quick start](#quick-start)
- [Bot framework](#bot-framework)
  - [Handlers and routing](#handlers-and-routing)
  - [Handler contexts](#handler-contexts)
  - [Commands](#commands)
  - [Buttons and callbacks](#buttons-and-callbacks)
  - [Deep links](#deep-links)
  - [Other updates](#other-updates)
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
- [Models](#models)
- [Errors and retries](#errors-and-retries)
- [Configuration](#configuration)
- [Threading and lifecycle](#threading-and-lifecycle)
- [Project layout](#project-layout)
- [Building and testing](#building-and-testing)
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
- File uploads in one call; the bot token is never sent to the upload host.
- Automatic retries with exponential backoff on 429/503, and on 502/504 and network errors of
  idempotent requests, plus re-sending while an attachment is still being processed (`attachment.not.ready`).
- Long polling with bounded parallelism, per-chat ordering and backpressure.
- Webhook handler with constant-time secret verification that plugs into Spring, Ktor, Javalin or servlets.
- Few dependencies: OkHttp, Jackson, kotlinx.coroutines. Logging goes to `System.Logger`, so it
  reaches SLF4J, Log4j or JUL through their usual bridges.

## Requirements and installation

- JDK 17 or newer.
- Kotlin 2.x if you use the library from Kotlin.

The library is not published to Maven Central yet. To use it from another project, install it to the
local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Then add it as a dependency:

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("ru.securitytrip:maxapi:0.1.0-SNAPSHOT")
}
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>ru.securitytrip</groupId>
    <artifactId>maxapi</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

OkHttp, Jackson and kotlinx.coroutines come in as transitive dependencies.

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
| `onMessage { }`                | any new message                                            | `MessageContext`     |
| `onMessage(filter) { }`        | new messages matching a predicate                          | `MessageContext`     |
| `onCallback("payload") { }`    | presses of buttons with this exact payload                 | `CallbackContext`    |
| `onCallback(filter) { }`       | button presses matching a predicate                        | `CallbackContext`    |
| `onCallback { }`               | any button press                                           | `CallbackContext`    |
| `onBotStarted { }`             | a user pressing "Start" or opening a deep link             | `BotStartedContext`  |
| `on<T> { }` / `onUpdate(T)`    | any update of type `T`; `Update` matches everything        | `UpdateContext<T>`   |
| `onError { error, update -> }` | exceptions thrown by handlers and polling errors           | —                    |

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
| `reply(text)`, `reply(body)`, `reply { DSL }` | send a message to the chat (or to the user if there is no chat) |
| `sendAction(action)`, `typing()` | show "typing…" or another activity                                  |
| `replyBlocking`, `sendActionBlocking` | Java versions                                                  |

`MessageContext` adds `message`, `text`, `messageId`, `command`, `commandArgs`,
`replyToMessage(...)` (reply with a quote) and `replyToMessageBlocking(...)`.

`CallbackContext` adds `callback`, `payload`, `message` (the message with the keyboard),
`answer(notification)`, `answer(CallbackAnswer)`, `editMessage(body, notification)` and their
`*Blocking` versions.

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

To show commands in the bot menu, set them once on startup:

```kotlin
bot.client.editMyCommands(listOf(BotCommand("start", "Start"), BotCommand("weather", "Weather forecast")))
```

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
bot.onCallback({ it.payload?.startsWith("order:") == true }) {
    val id = payload!!.removePrefix("order:")
    answer("Order $id")
}
```

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
payload (up to 128 characters) in a `bot_started` update:

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
  stop polling: `startPolling()` and `startBlocking()` throw them.

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

To go back to long polling, remove the subscription with `bot.client.unsubscribe(url)`.

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

All 33 operations, by area:

| Area          | Methods                                                                                                   |
|---------------|-----------------------------------------------------------------------------------------------------------|
| Bot           | `getMyInfo`, `editMyCommands`                                                                             |
| Chats         | `getChat`, `editChat`, `sendAction`, `getPinnedMessage`, `pinMessage`, `unpinMessage`, `leaveChat`        |
| Members       | `getMembership`, `getMembers`, `addMembers`, `removeMember`, `getAdmins`, `postAdmins`, `deleteAdmins`    |
| Messages      | `getMessages`, `getMessageById`, `sendMessage`, `editMessage`, `deleteMessage`, `answerOnCallback`, `getVideoAttachmentDetails` |
| Comments      | `getComments`, `getCommentById`, `sendComment`, `editComment`, `deleteComment`                            |
| Updates       | `getUpdates`, `getSubscriptions`, `subscribe`, `unsubscribe`                                              |
| Uploads       | `getUploadUrl`                                                                                            |

On top of them there are convenience methods: `sendMessageToChat`, `sendMessageToUser`,
`answerOnCallback(callbackId, notification)`, `upload`, `uploadImage`, `uploadVideo`, `uploadAudio`
and `uploadFile`.

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

The default HTTP client uses 15 s connect and 60 s read/write timeouts. `getUpdates` automatically
gets a longer read timeout, so long polling works with your own client too. A client you pass in is
never closed by the library.

`BotConfig`:

| Setting               | Default   | Description                                                      |
|-----------------------|-----------|------------------------------------------------------------------|
| `parallelism`         | `4`       | workers handling updates; one chat is always handled in order    |
| `queueCapacity`       | `64`      | queue size per worker; polling waits when it is full             |
| `pollTimeoutSeconds`  | `30`      | how long one `getUpdates` request waits, 0–90                    |
| `pollLimit`           | `100`     | maximum updates per request, 1–1000                              |
| `minPollInterval`     | 300 ms    | minimum interval between requests (the API requires ≥ 300 ms)    |
| `maxErrorBackoff`     | 30 s      | maximum pause after polling errors                               |

Logging uses `System.Logger` with the names `ru.securitytrip.maxapi.client` (retries, at `DEBUG`) and
`ru.securitytrip.maxapi.bot` (handler errors, at `ERROR`).

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
src/main/kotlin/ru/securitytrip/maxapi/
  model/    API models written from the specification, one subpackage per area:
            user, chat, message, attachment, keyboard, markup, update, subscription
  client/   MaxClient, configuration, exceptions, JSON, uploads, HTTP transport
  java/     MaxBlockingClient and MaxAsyncClient facades
  dsl/      message and keyboard builders, Buttons
  bot/      MaxBot, handler contexts, long polling, WebhookHandler
src/test/   tests (Kotlin and Java) against a mock HTTP server
examples/   echo bot in Kotlin and Java
```

The `client` and `model` packages do not depend on `bot`, so the client can be used on its own.

## Building and testing

```bash
./gradlew build
```

This compiles the library and the examples and runs the tests. The tests use OkHttp's
`MockWebServer` and need no token or network access. Every API method is checked through all three
facades (Kotlin, blocking, async), as is every update, attachment, button and markup type.

Library code has 100% line coverage, and `./gradlew check` (part of `build`) fails if a change lowers
it, or if branch coverage drops below 95% (Kotlin generates a few null-check branches that cannot be
reached). Coverage report (Kover), written to `build/reports/kover/html/index.html`:

```bash
./gradlew koverHtmlReport
```

When writing tests as `fun name() = runBlocking { ... }`, declare the return type explicitly:
`fun name(): Unit = runBlocking { ... }`. Otherwise a test whose last expression returns a value
(e.g. `assertIs<T>(...)`) gets a non-`Unit` return type, and JUnit silently skips it.

API reference is generated with [Dokka](https://github.com/Kotlin/dokka) from the KDoc comments and
the package descriptions in [docs/module.md](docs/module.md):

```bash
./gradlew dokkaGenerate
```

The result is in `build/dokka/html/index.html`. The same HTML is packaged into the `-javadoc.jar`
artifact.

Run the examples against a real bot:

```bash
MAX_BOT_TOKEN=your-token ./gradlew :examples:run
```

```bash
MAX_BOT_TOKEN=your-token ./gradlew :examples:run -PmainClass=ru.securitytrip.maxapi.examples.JavaEchoBot
```

## License

Copyright 2026 SecurityTrip

Licensed under the [Apache License, Version 2.0](LICENSE).
