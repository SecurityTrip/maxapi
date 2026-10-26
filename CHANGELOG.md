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

[Unreleased]: https://github.com/SecurityTrip/maxapi/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/SecurityTrip/maxapi/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/SecurityTrip/maxapi/compare/v0.5.0...v1.0.0
[0.5.0]: https://github.com/SecurityTrip/maxapi/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/SecurityTrip/maxapi/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/SecurityTrip/maxapi/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/SecurityTrip/maxapi/compare/v0.1.0-rc1...v0.2.0
[0.1.0-rc1]: https://github.com/SecurityTrip/maxapi/releases/tag/v0.1.0-rc1
