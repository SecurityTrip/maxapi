# Migrating to 1.0

1.0 freezes the public API: from now on, minor and patch versions keep source and binary
compatibility (see [Compatibility](README.en.md#compatibility)). This guide lists what changed since
the 0.x releases and how to update code written for them. Changes are grouped by the version that
introduced them; apply the sections for every version after the one you use.

The fastest path: update the dependency, recompile, and fix what the compiler reports. Most code
written for 0.1 compiles against 1.0 unchanged.

## Dependencies

The library is still `io.github.securitytrip:maxapi`. Since 0.4 there are more modules; the BOM keeps
their versions in line:

```kotlin
dependencies {
    implementation(platform("io.github.securitytrip:maxapi-bom:1.0.0"))
    implementation("io.github.securitytrip:maxapi")
    testImplementation("io.github.securitytrip:maxapi-test")
}
```

## From 0.2 to 0.3

**Recompile.** The handler registration methods (`onMessage`, `onCommand`, `onCallback`,
`onBotStarted`, `onUpdate`, `on`, `subscribedUpdateTypes`) moved from `MaxBot` to its new base class
`BaseRouter`. Calls compile as before, but classes compiled against 0.2 fail with
`NoSuchMethodError` until they are recompiled.

**Java filters.** The Java overloads take `Predicate<? super Context>` instead of
`Predicate<Context>`. Existing lambdas and `Predicate<MessageContext>` variables still fit. To combine
filters from `Filters`, start with the most specific one:
`Filters.hasText().and(Filters.fromUsers(42L))`.

**`BotConfig` has more parameters.** Kotlin code with named arguments and Java code with
`BotConfig.builder()` is unaffected; positional Kotlin calls with all six old arguments still compile.

Optional updates that make code shorter:

| 0.2                                                                 | 1.0                                                     |
|---------------------------------------------------------------------|---------------------------------------------------------|
| `bot.client.editMyCommands(listOf(BotCommand("start", "Start")))`  | `bot.onCommand("start", "Start") { ... }` and `bot.syncCommands()` |
| `onCallback({ it.payload?.startsWith("order:") == true })` and manual parsing | `CallbackData("order", "id")`, `onCallback(order)`, `data(order)` |
| a map of user states kept by hand                                   | `setState`, `stateData`, `Filters.state(...)`           |
| one handler that checks access first                                | `Router().use { ctx, next -> ... }` and `bot.include(router)` |

## From 0.3 to 0.4

**Build layout only.** The artifact, packages and API of `maxapi` did not change. New modules:
`maxapi-spring-boot-starter`, `maxapi-ktor`, `maxapi-test`, `maxapi-bom`.

If you wired the bot into Spring or Ktor by hand, the modules can replace that code:

- Spring: remove your `MaxClient`/`MaxBot` beans and the webhook controller, set `max.bot.*`
  properties, and register handlers in `MaxBotConfigurer` or `Router` beans. Your own `MaxClient`,
  `MaxBot` or `BotConfig` beans still take precedence.
- Ktor: replace the `post(path) { webhookHandler.handle(...) }` route with
  `maxWebhook(path, bot, secret)`.
- Tests that used `MockWebServer` to check what a bot sends can use `BotTester`.

## From 0.4 to 0.5

**Java handlers are interruptible.** They run with `runInterruptible(Dispatchers.IO)`, so a handler
cancelled by `close()` or by the new `BotConfig.handlerTimeout` gets an `InterruptedException` (or
an interrupted flag) instead of running to the end. Handlers that swallow `InterruptedException`
should restore the flag with `Thread.currentThread().interrupt()`.

**`MaxClientConfig.toString()`** also prints the rate limit.

New and off by default: `RateLimit`, `MarkerStore`, `UpdateDeduplicator`, `handlerTimeout`,
`MaxEventListener` and the `maxapi-micrometer` module. See
[Reliability and monitoring](README.en.md#reliability-and-monitoring).

## From 0.5 to 1.0

No API changes. 1.0 adds this guide, the compatibility policy and module documentation.

## From 1.4 to 1.5

1.5 keeps source and binary compatibility, but changes several defaults that made a bot easy to
break or to attack. Check each one; the old behaviour is a setting away.

**Dependencies.** The libraries are built for the versions the oldest supported Spring Boot (3.4)
manages: Kotlin 1.9.25 (2.0 API), kotlinx.coroutines 1.8.1, Jackson 2.18, Micrometer 1.14, OpenTelemetry
1.43, Lettuce 6.4. Applications get these or newer versions, never older ones, so the `NoSuchMethodError`
of 1.4.0 in Spring Boot applications (`runBlockingK`) is gone. The starter works with Spring Boot 3.4+ and 4.x. The starter checks on startup that the
application does not have older Kotlin, coroutines or Jackson than `maxapi` needs.

**Handlers of a chat no longer hold up other chats.** Every chat has its own queue, and up to
`BotConfig.parallelism` handlers run at once, now 64 instead of 4 workers. When a chat has
`queueCapacity` updates waiting, long polling drops its further updates and reports a
`QueueOverflowException` to `onError`, instead of stopping for every chat; with a `MarkerStore` the
saved marker stays before the dropped update, so that it comes again after a restart. Webhook updates handled in
the background (`handleAsync`, `max.bot.webhook.async=true`) go through the same queues, so they keep
the order of a chat; a full queue answers 503 and Max redelivers.

**`handlerTimeout` is one minute by default.** Handlers that legitimately run longer need
`BotConfig(handlerTimeout = ...)`, or `null` for no limit (`max.bot.handler-timeout=0` in Spring Boot).

**Button presses are answered.** A press that the handlers left unanswered (no handler matched, the
handler failed or did not call `answer`) gets an empty answer after the handler, so the user's client
stops showing it as loading. Turn it off with `BotConfig(autoAnswerCallbacks = false)` or
`max.bot.auto-answer-callbacks=false`. In `BotTester`, such presses now record an empty answer too.

**`WebAppPhone.verify` checks the age.** `authDate` may be at most one hour old by default, as for
`initData`. Pass `maxAge = null` to check only the signature.

**`InMemoryStateStorage` forgets old states.** A state not changed for a day expires, and at most
100 000 states are kept. `InMemoryStateStorage(maxSize, ttl = null)` keeps them as before.

**`MaxClientConfig.baseUrl` must use https,** except for the local host (`localhost`, loopback
addresses, `*.localhost`, `*.test`).

**The Russian Trusted Root CA is trusted for `max.ru` hosts only.** Upload hosts and other servers are
checked against the JVM's own trust store.

**Deduplication keys of other updates changed** (not of messages and button presses): they now hash
all fields. Right after the upgrade, a redelivery of such an update seen before it is not recognized.

Spring Boot starter:

- **The webhook always has a secret.** Without `max.bot.webhook.secret`, a secret derived from the
  token is used (`WebhookHandler.secretFor(token)`); with `max.bot.webhook.url` the bot subscribes with
  it. If you subscribe yourself without a secret, subscribe again with that one or set your own.
  Bodies over `max.bot.webhook.max-body-size` (1 MB) get 413, and the secret is checked before the
  body is read.
- **`max.bot.rate-limit` is `max` by default.** Set `none` for the old behaviour.
- **Startup checks the token** with `getMyInfo`, unless `max.bot.mode=none` without `sync-commands`: a
  rejected token fails the start with an explanation. When the API cannot be reached within
  `max.bot.startup-timeout` (10 seconds), the application still starts and the menu sync and the
  webhook subscription are retried in the background.
- **Stopping waits up to `spring.lifecycle.timeout-per-shutdown-phase`** for the updates already
  received; then closing the bot cancels the handlers.
- **Without a deduplicator bean, an `InMemoryUpdateDeduplicator` is used.**
- **`max.bot.storage=jdbc|redis` without its module fails the start** instead of keeping the state in memory.
- **A blank `max.bot.token` turns the bot off** with a warning instead of failing the context.
- New settings: `max.bot.parallelism`, `queue-capacity`, `handler-timeout`, `poll-timeout`,
  `auto-answer-callbacks`, `shutdown-timeout`, `startup-timeout`, `proxy`, `connect-timeout`, `read-timeout`,
  `webhook.max-body-size`, and `BotConfigCustomizer` beans. `@MaxBotTest` starts a test context without
  a real token and without calling the API.
