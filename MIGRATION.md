# Migrating to 1.0

1.0 freezes the public API: from now on, minor and patch versions keep source and binary
compatibility (see [Compatibility](README.md#compatibility)). This guide lists what changed since
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
[Reliability and monitoring](README.md#reliability-and-monitoring).

## From 0.5 to 1.0

No API changes. 1.0 adds this guide, the compatibility policy and module documentation.
