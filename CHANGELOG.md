# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). Since 1.0, minor and patch versions keep the public API
compatible; before 1.0, minor versions could contain breaking changes, listed under **Changed**
(see [MIGRATION.md](MIGRATION.md)).

The release workflow publishes the section of a version as its GitHub release notes, so every
release needs a `## [x.y.z]` section here before it is tagged. Pre-releases such as `x.y.z-rc1` use
their own section if there is one, and the `x.y.z` section otherwise.

## [Unreleased]

## [1.4.0] - 2026-10-30

The current rules of Max, mini app backends, tools for writing bots, and production operations:
health, tracing, a WebFlux webhook, native image and non-blocking deduplication.

### Added

- Spring Boot starter: the health contributor `max` when Spring Boot Actuator is present
  (`MaxHealthAutoConfiguration`). Its part `bot` (`MaxBotHealthIndicator`) is `DOWN` when long polling
  ended (with the error in `pollingError`), when webhook mode has no endpoint or the subscription to
  `max.bot.webhook.url` is missing, and when the API does not answer or rejects the token; the request
  to the API (`getSubscriptions` or `getMyInfo`) is made at most once per `max.bot.health.cache-ttl`
  (30 seconds) and waits up to `max.bot.health.timeout` (5 seconds). Its part `storage` checks the
  database or Redis of `max.bot.storage=jdbc|redis`. `management.health.max.enabled=false` turns it off.
- New module `maxapi-opentelemetry`: `MaxTracing` records API requests as client spans
  (`GET /chats/{id}` with `http.request.method`, `url.template`, `http.response.status_code`,
  `server.address`, `server.port`, `http.request.resend_count`, `error.type`), dispatched updates as
  consumer spans (`process message_created` with `messaging.system=max`, `max.update.type`,
  `max.chat.id`, `max.update.handled`, `error.type`) and renewed webhook subscriptions as
  `max.webhook.resubscribe`. The span of an update is current in the coroutines of its handler, so
  the requests the handler makes are its children; a webhook update handled synchronously continues
  the trace of the server span. The starter creates `MaxTracing` when the application has an
  `OpenTelemetry` bean (`MaxTracingAutoConfiguration`).
- `MaxEventListener.updateContext(update)`: coroutine context elements the bot adds while it
  dispatches an update, e.g. an OpenTelemetry context or an SLF4J `MDCContext`; `composite` combines
  the contexts of its listeners. The `onUpdate` event of the update is called within that context.
- Spring Boot starter: the webhook endpoint in reactive web applications (Spring WebFlux), with the
  same answers as the servlet one: 200, 401, 400 and 503. Handlers run on `Dispatchers.Default`, and
  with `max.bot.webhook.async=false` the response waits for them without blocking a thread.
- GraalVM native image: `maxapi` has reflection metadata for all model classes, the bundled root CA
  and the Kotlin built-ins in `META-INF/native-image/io.github.securitytrip/maxapi`; the starter
  registers the same hints with Spring AOT (`MaxRuntimeHints` in `META-INF/spring/aot.factories`).
- `SuspendUpdateDeduplicator` with `suspend fun markSeen(update)`, a non-blocking variant of
  `UpdateDeduplicator`, set with `BotConfig.suspendDeduplicator` (also in the builder). The bot calls
  `markSeen` of a deduplicator that implements both interfaces. The starter passes a
  `SuspendUpdateDeduplicator` bean to the bot when there is no `UpdateDeduplicator` bean.
- `MaxBot.isPolling` and `MaxBot.pollingFailure`: whether long polling runs, and the error that ended it.

- Mini app backends, in the new package `ru.securitytrip.maxapi.webapp` of `maxapi` with no new
  dependencies. `WebAppData.validate(initData, botToken, maxAge)` checks `window.WebApp.initData`
  as https://dev.max.ru/docs/webapps/validation describes (values URL-decoded once, exactly one
  `hash`, `HMAC_SHA256(HMAC_SHA256("WebAppData", botToken), sorted key=value lines)`, `auth_date`
  no older than `maxAge`, one hour by default) and returns the user, the chat, `query_id`,
  `auth_date`, `start_param`, `ip` and all other fields; it throws `WebAppDataException` with a
  `reason`: `MALFORMED`, `MISSING_HASH`, `DUPLICATE_HASH`, `INVALID_SIGNATURE` or `EXPIRED`.
  `WebAppData.verifySignature` only checks the signature and returns a `Boolean`.
- `WebAppPhone.verify` checks the phone number from `requestContact()` of MAX Bridge
  (`HMAC_SHA256(botToken, "authDate=…\nphone=…\nuserId=…")`), and `ContactAttachment.verify` (also on
  `ContactAttachmentPayload`) the contact shared with a `request_contact` button
  (`HMAC_SHA256(botToken, vcf_info)`; checked against the live API). All hashes are compared in
  constant time.
- `MaxLinks` builds deep links: `bot`, `botStart` (`?start=`, 1–128 characters), `miniApp`
  (`?startapp=`, up to 512 characters of `[A-Za-z0-9_-]`) and `share` (`:share?text=`), and throws
  `IllegalArgumentException` for a payload Max would drop. `MaxLimits.BOT_START_PAYLOAD_LENGTH`,
  `MaxLimits.isValidOpenAppPayload` and `MaxLimits.checkOpenAppPayload` are public.
- Spring Boot starter: controller parameters annotated with `@MaxWebAppUser` get the checked
  `WebAppUser` or `WebAppData`, and requests without valid `initData` get 401. `max.bot.web-app.source`
  says where `initData` is (`header` by default, `parameter` or `body`), with `max.bot.web-app.header`
  (`X-Max-Init-Data`), `max.bot.web-app.parameter` (`initData`) and `max.bot.web-app.max-age` (`1h`).
- `maxapi-ktor`: the `MaxWebApp` route plugin answers 401 to calls without valid `initData` (from the
  `X-Max-Init-Data` header by default), and `call.maxInitData()` and `call.maxWebAppUser()` return
  the checked data.
- `examples-spring` has a mini app backend with a page that calls it, an `open_app` button, a
  `startapp` link and a verified `request_contact` contact.

- `MaxLimits` holds the limits of Max, and `MaxClient` checks them before sending: text of a message
  or a comment up to 4000 characters; keyboards of up to 30 rows, 1 to 210 buttons and 7 buttons in
  a row (3 in a row with a `link`, `open_app`, `request_geo_location` or `request_contact` button,
  as the live API enforces it); button text
  of 1–128 characters, `callback` and `clipboard` payloads up to 1024, `link` URLs up to 2048,
  `open_app` payloads up to 512 of `[A-Za-z0-9_-]`; up to 32 bot commands with names of 1–64 and
  descriptions of 1–128 characters. `sendMessage`, `editMessage`, `answerOnCallback`, `sendComment`,
  `editComment`, `editMyCommands` and `MaxBot.syncCommands` throw `IllegalArgumentException` with
  the broken limit instead of making a request. Lengths are counted in UTF-16 code units, as the
  server counts them (checked against the live API: an emoji takes 2 characters).
- `MaxClient.sendLongMessage` (with `sendLongMessageToChat` and `sendLongMessageToUser`, in the
  blocking and async facades too) and `UpdateContext.replyLong` (`replyLongBlocking`) send text
  longer than 4000 characters as several messages; the keyboard and other attachments go with the
  last one. `TextSplitter` does the splitting: between paragraphs, then lines, then words, never
  inside a surrogate pair, and with Markdown and HTML markup closed and opened again at every cut.
- `RateLimit.MAX`: the limits of Max, 30 requests per second and 2 answers to button presses per
  second in a chat. `RateLimit.withPerChatAnswers` sets the per-chat limit of answers in any
  `RateLimit`; `MaxBot` tells the client the chat of every press it dispatches, because
  `POST /answers` carries only the `callback_id`.
- `MaxBot.ensureWebhook` (and `ensureWebhookBlocking`) subscribes to a webhook again if Max dropped
  the subscription after 8 hours without successful deliveries, and reports it to the new
  `MaxEventListener.onWebhookResubscribe`. `maxapi-micrometer` counts it in
  `max.bot.webhook.resubscriptions`.
- Spring Boot starter: `max.bot.rate-limit=none|max` (default `none`) sets `RateLimit.MAX`, and in
  webhook mode with `max.bot.webhook.url` the bot checks its subscription every
  `max.bot.webhook.check-interval` (default 10 minutes, `0` turns it off) and subscribes again when
  it is gone.

- Scenes: `scene("name") { ... }` (`Scene.builder` in Java) describes a wizard on top of the dialog
  state, so it works with any `StateStorage`. Steps have a prompt, checks (`validate`, with an error
  text or an `onInvalid` reaction) and an optional `input` that reads other kinds of answers; answers
  are kept in `DialogState.data` under the step names. A scene is entered with `enterOnCommand`,
  `enterOnCallback` or `Scene.enter(ctx, data)`; `/back` or `backButton` returns a step, `/cancel` or
  `cancelButton` runs `onCancel`, and `option(text, value)` buttons answer only the current step.
  `BaseRouter.include(scene)` adds it to a bot; `SceneContext` gives prompts and callbacks the handler
  context with `answers`. The `QuestionnaireBot` example is rewritten on a scene, with a `BotTester`
  test.
- Keyboards: `Paginator` shows a long list page by page with a `◀ 2/7 ▶` row packed by `CallbackData`
  (page and argument), and `Confirmation` is a "Yes / No" question with `yes()` and `no()` filters.
  Both keep to the keyboard limits of Max: 7 buttons in a row, 3 with a wide button, 30 rows.
- `Translations` picks texts by `UpdateContext.locale` with a fallback language, from maps or
  `ResourceBundle`s (`Translations.fromBundles`), with `{0}` and `{name}` placeholders and plural
  forms (`one`/`few`/`many` for Russian, Ukrainian and Belarusian, `one`/`other` otherwise).
- Chat registry: Max has no method that lists the chats of a bot, so with `BotConfig.chatStore` set
  the bot remembers them from `bot_added`, `bot_removed`, `bot_started`, `bot_stopped`,
  `chat_title_changed` and the first message from an unknown chat, and `MaxBot.chats(vararg types)`
  (`chatsBlocking`) lists the active ones as `KnownChat`. Records keep the time of their event, so a
  repeated or late delivery never undoes a newer one. Stores: `InMemoryChatStore`,
  `BlockingChatStore` for Java, `JdbcChatStore` in `maxapi-jdbc` and `RedisChatStore` in
  `maxapi-redis`. These update types are requested even without handlers for them.
- Broadcasts: `Broadcaster.send(id, targets, message)` (`sendBlocking`) sends a message, the same or
  per target, to users and chats (`BroadcastTarget`) at up to 25 requests per second by default,
  waits for `Retry-After` on 429, records 403 and 404 as unavailable without retrying, and returns a
  `BroadcastReport`. The outcome of every target goes to a `BroadcastStore` right away, so running the
  broadcast again with the same id continues where it stopped. Stores: `InMemoryBroadcastStore`,
  `BlockingBroadcastStore`, `JdbcBroadcastStore` and `RedisBroadcastStore` (with an optional TTL).
- Spring Boot starter: `max.bot.storage=jdbc|redis` also creates the chat store (`<prefix>chat`,
  `<prefix>chats`) and the broadcast store (`<prefix>broadcast`, `<prefix>broadcast:`); a `ChatStore`
  bean goes to the bot, and a `Broadcaster` bean uses the `BroadcastStore` bean (in memory without one).
- `maxapi-test`: `TestUpdates.botAdded`, `botRemoved`, `botStopped` and `chatTitleChanged`.

### Changed

- `RedisUpdateDeduplicator` and `JdbcUpdateDeduplicator` also implement `SuspendUpdateDeduplicator`,
  so the bot no longer blocks its workers on them: Redis is asked with the asynchronous API of
  Lettuce, JDBC runs on `Dispatchers.IO`. `isNew` still works as before.
- Spring Boot starter: the storage deduplicators of `max.bot.storage` are not created when the
  application has its own `UpdateDeduplicator` or `SuspendUpdateDeduplicator` bean.
- `Buttons.openApp` and `openApp` of the keyboard DSL check the payload when the button is created,
  not only when the message is sent.
- Messages, comments, answers and commands that break the limits of Max now fail with
  `IllegalArgumentException` before the request instead of `MaxApiException` (400) from the server.
- With a per-chat limit set (`RateLimit.perChatRequestsPerSecond`), answers to button presses
  dispatched by `MaxBot` count towards the limit of their chat.

### Deprecated

- `MaxClient.addMembers` and its blocking and async versions: Max restricted
  `POST /chats/{chatId}/members` on 2026-09-09 and removed it on 2026-09-30. There is no replacement.

## [1.3.0] - 2026-10-26

Storage for production: dialog states, the polling marker and seen updates in SQL or Redis.

### Added

- `maxapi-jdbc`: `JdbcStateStorage`, `JdbcMarkerStore` and `JdbcUpdateDeduplicator` keep dialog
  states, the polling marker and the keys of handled updates in any SQL database through a
  `DataSource`. Only standard SQL is used; `createTable()` creates a missing table.
- `maxapi-redis`: `RedisStateStorage` (with an optional TTL), `RedisMarkerStore` and
  `RedisUpdateDeduplicator` on Lettuce.
- Spring Boot starter: `max.bot.storage=jdbc|redis` with `max.bot.jdbc.*` and `max.bot.redis.*`
  creates those stores, and `StateStorage`, `MarkerStore` and `UpdateDeduplicator` beans are used by
  the auto-configured bot. The bot settings are now a `BotConfig` bean (`maxBotConfig`).
- Both modules are in `maxapi-bom`.

## [1.2.0] - 2026-10-26

Fixes from running the examples against the real API.

### Added

- `MaxBot.deleteWebhook()` (and `deleteWebhookBlocking()`) removes every webhook subscription of
  the bot.

### Fixed

- `MessageContext.text` is `null` for photos, stickers and other messages without text. Max sends an
  empty string for them, so `text ?: "..."` replied with an empty message (rejected with
  `400 proto.payload`) and `Filters.hasText()` let such messages through.
- Long polling no longer receives nothing while the bot is subscribed to a webhook: Max answers
  `getUpdates` with empty pages then, so `startPolling()` now checks the subscriptions first and
  throws `MaxException`, as documented.
- `sendMessage` rejects a message without text, attachments or a forwarded message with
  `IllegalArgumentException` instead of sending it and getting `400 proto.payload: errors.required`.
- The Ktor example unsubscribes from the webhook when it stops and logs through `slf4j-simple`.
- The echo examples change the message on "Like" too, since the web client of Max does not show
  callback notifications.

### Changed

- Documented that the web client of Max shows neither the command menu nor callback notifications.

## [1.1.0] - 2026-10-26

### Added

- `MaxTls` and `MaxClientConfig.trustRussianRootCa` (`max.bot.trust-russian-root-ca` in the Spring Boot
  starter): the library bundles the Russian Trusted Root CA, which the certificate of
  `platform-api2.max.ru` is issued under, and its own HTTP client trusts it in addition to the JVM's
  certificates. Connecting no longer needs a custom trust store.

## [1.0.0] - 2026-10-24

The public API is stable from this version on.

### Added

- [MIGRATION.md](MIGRATION.md) with the changes since 0.x, and a compatibility policy in README.
- CONTRIBUTING.md.
- Module descriptions in the API reference for the starter, Ktor, test kit and Micrometer modules.

## [0.5.0] - 2026-10-24

### Added

- `RateLimit` (`MaxClientConfig.rateLimit`): client-side token bucket for all requests and per chat.
- `MarkerStore` (`BotConfig.markerStore`) with `InMemoryMarkerStore`, `FileMarkerStore` and
  `BlockingMarkerStore`: long polling continues after a restart; the marker is saved only after the
  updates before it were handled.
- `UpdateDeduplicator` (`BotConfig.deduplicator`) with `InMemoryUpdateDeduplicator` against repeated
  webhook deliveries.
- `BotConfig.handlerTimeout` and `HandlerTimeoutException`.
- `MaxEventListener` with `RequestEvent` and `UpdateEvent` in `MaxClientConfig` and `BotConfig`.
- `maxapi-micrometer` with `MaxMetrics`; the Spring Boot starter registers it when a `MeterRegistry`
  exists and attaches `MaxEventListener` beans.

### Changed

- Java handlers run with `runInterruptible`, so they are interrupted when cancelled (timeout, `close()`).

## [0.4.0] - 2026-10-24

### Added

- `maxapi-spring-boot-starter`: `max.bot.*` properties, auto-configured `MaxClient` and `MaxBot`,
  handlers from `MaxBotConfigurer` and `Router` beans, long polling with graceful shutdown or a
  webhook endpoint with optional subscription on startup, `sync-commands`.
- `maxapi-ktor`: `Route.maxWebhook(path, bot, secret)`.
- `maxapi-test`: `BotTester` runs handlers without a network and records API calls;
  `TestUpdates` builds updates for tests.
- `maxapi-bom` with the versions of all modules.
- Spring Boot and Ktor examples.

### Changed

- The build has several modules: the library is in `maxapi/` and is still published as
  `io.github.securitytrip:maxapi`. Shared build settings are convention plugins in `build-logic/`.
- The API reference on GitHub Pages covers all modules.

## [0.3.0] - 2026-10-24

### Added

- Middleware: `use { ctx, next -> }` in Kotlin and the `Middleware` interface in Java, around the
  handlers of a bot or a router.
- `Router` for feature modules, added with `include(router)`; routers can be nested and have their
  own middleware.
- `Filters` (text, regular expressions, chat type, attachments, payload prefix, users, chats,
  dialog state) and `Filter` with `and`, `or` and `!`.
- Dialog state: `state`, `stateData`, `setState`, `updateStateData` and `clearState` on every context,
  `StateStorage` with `InMemoryStateStorage` and `BlockingStateStorage`, `BotConfig.stateStorage`.
- `CallbackData` for typed button payloads, `onCallback(callbackData)` and `CallbackContext.data`.
- `onCommand(name, description)`, `commands()` and `MaxBot.syncCommands()` for the bot menu.
- `UpdateContext.locale`.
- Questionnaire example with all of the above.

### Changed

- The registration methods (`on*`, `subscribedUpdateTypes`) moved to the new base class
  `BaseRouter` of `MaxBot` and `Router`. Source code compiles unchanged; code compiled against 0.2
  must be recompiled.
- Java filter overloads accept `Predicate<? super Context>`.

## [0.2.0] - 2026-10-24

### Added

- Binary compatibility check: the public API is recorded in `api/maxapi.api`, and `./gradlew check`
  fails when it changes without `./gradlew apiDump`.
- `spec/schema.yaml` and a test that checks the client, the model subtypes and the enums against it.
- API reference on GitHub Pages, published with every stable release.
- Release notes are taken from this file.
- Dependabot updates for Gradle dependencies and GitHub Actions.

### Changed

- The examples set `bot.username` from `getMyInfo`, so addressed commands need no extra request.

## [0.1.0-rc1] - 2026-10-24

First release candidate.

### Added

- `MaxClient` with all 33 methods of Max Bot API 0.0.33 as suspend functions, plus the blocking
  `MaxBlockingClient` and the `CompletableFuture`-based `MaxAsyncClient` for Java.
- Typed models for users, chats, messages, attachments, keyboards, markup, updates and
  subscriptions; unknown types and enum values are kept instead of failing.
- File uploads in one call, retries with backoff and `Retry-After` support.
- `MaxBot`: command, message and callback handlers, long polling with per-chat ordering and
  graceful stop, webhook handler with secret verification.
- Kotlin DSL and Java builders for messages and keyboards.

[Unreleased]: https://github.com/SecurityTrip/maxapi/compare/v1.4.0...HEAD
[1.4.0]: https://github.com/SecurityTrip/maxapi/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/SecurityTrip/maxapi/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/SecurityTrip/maxapi/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/SecurityTrip/maxapi/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/SecurityTrip/maxapi/compare/v0.5.0...v1.0.0
[0.5.0]: https://github.com/SecurityTrip/maxapi/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/SecurityTrip/maxapi/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/SecurityTrip/maxapi/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/SecurityTrip/maxapi/compare/v0.1.0-rc1...v0.2.0
[0.1.0-rc1]: https://github.com/SecurityTrip/maxapi/releases/tag/v0.1.0-rc1
