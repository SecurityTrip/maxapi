package ru.securitytrip.maxapi.bot

import ru.securitytrip.maxapi.model.update.Update

/**
 * Update handler for Java, registered with the `MaxBot.on*` methods.
 *
 * Handlers run on `Dispatchers.IO`, so they may block: call the `*Blocking` methods of the context,
 * talk to a database, and so on. Exceptions thrown by a handler (checked ones included) are passed
 * to the error handler of the bot and do not stop it.
 *
 * ```java
 * bot.onCommand("start", ctx -> ctx.replyBlocking("Hello!"));
 * ```
 *
 * Kotlin code passes `suspend` lambdas to the same methods instead.
 */
public fun interface Handler<C> {
    @Throws(Exception::class)
    public fun handle(ctx: C)
}

/**
 * Error handler for Java, registered with `MaxBot.onError`.
 *
 * It receives exceptions thrown by update handlers, together with the [Update] being handled,
 * and long polling errors, with `update = null`.
 */
public fun interface ErrorHandler {
    public fun handle(error: Throwable, update: Update?)
}

/**
 * Middleware for Java, registered with `MaxBot.use` or `Router.use`. Runs on `Dispatchers.IO`.
 *
 * ```java
 * bot.use((ctx, next) -> {
 *     long started = System.nanoTime();
 *     try {
 *         next.proceed();
 *     } finally {
 *         log.info(ctx.getUpdate().getUpdateType() + ": " + (System.nanoTime() - started) / 1_000_000 + " ms");
 *     }
 * });
 * ```
 */
public fun interface Middleware {
    /** Runs around the handler: call [Next.proceed] to run it, or return without calling it to skip it. */
    @Throws(Exception::class)
    public fun handle(ctx: UpdateContext<*>, next: Next)
}

/** The rest of the chain passed to a [Middleware]: further middleware and the handler. */
public fun interface Next {
    /** Runs the rest of the chain and returns when it completes; exceptions of the handler propagate. */
    @Throws(Exception::class)
    public fun proceed()
}
