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
