package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.update.updateTypeOf
import ru.securitytrip.maxapi.model.user.BotCommand
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Predicate

/**
 * Handler registration shared by [MaxBot] and [Router]. Registration methods return the object they
 * are called on (`R`), so calls can be chained.
 *
 * ## Routing
 *
 * Handlers and included routers are checked in registration order and **only the first matching
 * handler runs**. Register specific handlers (commands, exact payloads, states) before catch-all
 * ones like `onMessage { }`. Updates no handler matches are ignored.
 *
 * Every registration method has two overloads: a Kotlin `suspend` lambda with the context as
 * receiver, and a Java [Handler] that runs on `Dispatchers.IO`.
 *
 * ## Middleware
 *
 * [use] wraps the handlers of this router (and of the routers it includes) with code that runs before
 * and after them: logging, timing, access checks. Middleware runs only when a handler matched.
 */
public abstract class BaseRouter<R : BaseRouter<R>> internal constructor() {
    private sealed interface Entry

    /** A handler; `prepare` returns the context and the action for a matching update, `null` otherwise. */
    private class Route(
        val type: Class<out Update>,
        val command: BotCommand?,
        val prepare: (Dispatch) -> Matched?,
    ) : Entry

    private class Child(val router: BaseRouter<*>) : Entry

    private val entries = CopyOnWriteArrayList<Entry>()
    private val middlewares = CopyOnWriteArrayList<suspend (UpdateContext<*>, suspend () -> Unit) -> Unit>()

    @Suppress("UNCHECKED_CAST")
    private val self: R get() = this as R

    // ------------------------------------------------------------ messages

    /** Handles every new message that earlier handlers did not take. */
    public fun onMessage(handler: suspend MessageContext.() -> Unit): R = onMessage({ true }, handler)

    /** Java version of [onMessage]. */
    public fun onMessage(handler: Handler<MessageContext>): R = onMessage({ true }, handler.toSuspend())

    /**
     * Handles new messages matching [filter], a lambda or a [Filter] from [Filters]:
     *
     * ```kotlin
     * bot.onMessage({ it.text?.contains("price", ignoreCase = true) == true }) { reply("See our catalog") }
     * bot.onMessage(Filters.chatType(ChatType.DIALOG) and Filters.hasText()) { ... }
     * ```
     */
    public fun onMessage(filter: (MessageContext) -> Boolean, handler: suspend MessageContext.() -> Unit): R =
        route(MessageCreatedUpdate::class.java, MessageContext::class.java, ::MessageContext, filter, handler)

    /** Java version of [onMessage] with a filter. */
    public fun onMessage(filter: Predicate<in MessageContext>, handler: Handler<MessageContext>): R =
        onMessage(filter::test, handler.toSuspend())

    /**
     * Handles the command `/name`. Matching ignores case, so `onCommand("start")` handles `/start` and
     * `/Start`. A command with a suffix, like `/start@my_bot`, is handled only if the suffix is this
     * bot's [MaxBot.username]. Arguments after the command are available as [MessageContext.commandArgs].
     *
     * @param name command name, with or without the leading `/`.
     */
    public fun onCommand(name: String, handler: suspend MessageContext.() -> Unit): R =
        command(name, null, handler)

    /** Java version of [onCommand]. */
    public fun onCommand(name: String, handler: Handler<MessageContext>): R = command(name, null, handler.toSuspend())

    /**
     * Same as [onCommand], and adds the command with [description] to [commands], which
     * [MaxBot.syncCommands] shows in the bot menu.
     */
    public fun onCommand(name: String, description: String, handler: suspend MessageContext.() -> Unit): R =
        command(name, description, handler)

    /** Java version of [onCommand] with a description. */
    public fun onCommand(name: String, description: String, handler: Handler<MessageContext>): R =
        command(name, description, handler.toSuspend())

    private fun command(name: String, description: String?, handler: suspend MessageContext.() -> Unit): R {
        val command = name.removePrefix("/").lowercase()
        require(command.isNotBlank()) { "Command name must not be blank" }
        return route(
            MessageCreatedUpdate::class.java, MessageContext::class.java, ::MessageContext,
            { it.command == command }, handler, description?.let { BotCommand(command, it) },
        )
    }

    // ----------------------------------------------------------- callbacks

    /** Handles every callback button press that earlier handlers did not take. */
    public fun onCallback(handler: suspend CallbackContext.() -> Unit): R = onCallback({ true }, handler)

    /** Java version of [onCallback]. */
    public fun onCallback(handler: Handler<CallbackContext>): R = onCallback({ true }, handler.toSuspend())

    /** Handles presses of buttons whose payload equals [payload]. */
    public fun onCallback(payload: String, handler: suspend CallbackContext.() -> Unit): R =
        onCallback({ it.payload == payload }, handler)

    /** Java version of [onCallback] with a payload. */
    public fun onCallback(payload: String, handler: Handler<CallbackContext>): R =
        onCallback(payload, handler.toSuspend())

    /**
     * Handles presses of buttons whose payload was packed by [data]; read the fields with
     * [CallbackContext.data]:
     *
     * ```kotlin
     * val order = CallbackData("order", "id", "action")
     * bot.onCallback(order) { val (id, action) = data(order).let { it["id"] to it["action"] } ... }
     * ```
     */
    public fun onCallback(data: CallbackData, handler: suspend CallbackContext.() -> Unit): R =
        onCallback({ data.matches(it.payload) }, handler)

    /** Java version of [onCallback] with [CallbackData]. */
    public fun onCallback(data: CallbackData, handler: Handler<CallbackContext>): R =
        onCallback(data, handler.toSuspend())

    /**
     * Handles button presses matching [filter], e.g. a payload prefix:
     *
     * ```kotlin
     * bot.onCallback(Filters.payloadStartsWith("buy:")) { ... }
     * ```
     */
    public fun onCallback(filter: (CallbackContext) -> Boolean, handler: suspend CallbackContext.() -> Unit): R =
        route(MessageCallbackUpdate::class.java, CallbackContext::class.java, ::CallbackContext, filter, handler)

    /** Java version of [onCallback] with a filter. */
    public fun onCallback(filter: Predicate<in CallbackContext>, handler: Handler<CallbackContext>): R =
        onCallback(filter::test, handler.toSuspend())

    // ------------------------------------------------------- other updates

    /** Handles a user starting the bot, including via a deep link (see [BotStartedContext.payload]). */
    public fun onBotStarted(handler: suspend BotStartedContext.() -> Unit): R =
        route(BotStartedUpdate::class.java, BotStartedContext::class.java, ::BotStartedContext, { true }, handler)

    /** Java version of [onBotStarted]. */
    public fun onBotStarted(handler: Handler<BotStartedContext>): R = onBotStarted(handler.toSuspend())

    /**
     * Handles every update of [type] or its subclasses. Pass `Update::class.java` to handle all updates.
     *
     * ```java
     * bot.onUpdate(BotAddedToChatUpdate.class, ctx -> ctx.replyBlocking("Hi everyone!"));
     * ```
     */
    public fun <U : Update> onUpdate(type: Class<U>, handler: suspend UpdateContext<U>.() -> Unit): R =
        route(type, UpdateContext::class.java, { update: U, dispatch -> UpdateContext(update, dispatch) }, { true }, handler)

    /** Java version of [onUpdate]. */
    public fun <U : Update> onUpdate(type: Class<U>, handler: Handler<UpdateContext<U>>): R =
        onUpdate(type, handler.toSuspend())

    /**
     * Handles every update of type [U]:
     *
     * ```kotlin
     * bot.on<BotAddedToChatUpdate> { reply("Hi everyone!") }
     * bot.on<MessageEditedUpdate> { println("Edited: ${update.message.body?.text}") }
     * ```
     */
    public inline fun <reified U : Update> on(noinline handler: suspend UpdateContext<U>.() -> Unit): R =
        onUpdate(U::class.java, handler)

    // ---------------------------------------------------------- structure

    /**
     * Adds middleware around the handlers of this router and of the routers it includes.
     * It receives the context of the matched handler and `next`, which runs the handler (and the
     * middleware registered after this one). Not calling `next` skips the handler:
     *
     * ```kotlin
     * bot.use { ctx, next ->
     *     val started = System.nanoTime()
     *     try { next() } finally { log("${ctx.update.updateType}: ${(System.nanoTime() - started) / 1_000_000} ms") }
     * }
     * adminRouter.use { ctx, next -> if (ctx.user?.userId in admins) next() else ctx.reply("Admins only") }
     * ```
     *
     * Middleware runs in registration order, the first registered is the outermost.
     */
    public fun use(middleware: suspend (ctx: UpdateContext<*>, next: suspend () -> Unit) -> Unit): R {
        middlewares += middleware
        return self
    }

    /** Java version of [use]; the middleware runs on `Dispatchers.IO`. */
    public fun use(middleware: Middleware): R = use { ctx, next ->
        withContext(Dispatchers.IO) { middleware.handle(ctx) { runBlocking { next() } } }
    }

    /**
     * Adds the handlers of [router] at this point of the handler list: they are checked after the
     * handlers registered here before, and before those registered after. Middleware of [router]
     * applies only to its own handlers.
     *
     * @throws IllegalArgumentException if [router] is this router or already includes it.
     */
    public fun include(router: Router): R {
        val other: BaseRouter<*> = router
        require(other !== this && !other.includes(this)) { "A router cannot include itself" }
        entries += Child(router)
        return self
    }

    private fun includes(router: BaseRouter<*>): Boolean =
        entries.any { it is Child && (it.router === router || it.router.includes(router)) }

    /**
     * Commands registered with a description, in registration order and including those of
     * included routers. A name registered twice is listed once, with its first description.
     */
    public fun commands(): List<BotCommand> {
        val result = LinkedHashMap<String, BotCommand>()
        collectCommands(result)
        return result.values.toList()
    }

    private fun collectCommands(into: MutableMap<String, BotCommand>) {
        for (entry in entries) when (entry) {
            is Route -> entry.command?.let { into.putIfAbsent(it.name, it) }
            is Child -> entry.router.collectCommands(into)
        }
    }

    /**
     * Update types the registered handlers can process, or `null` if some handler accepts any
     * update (e.g. `onUpdate(Update::class.java)`). Long polling and [MaxBot.setWebhook] use it to
     * request only these types.
     */
    public fun subscribedUpdateTypes(): Set<UpdateType>? {
        val types = LinkedHashSet<UpdateType>()
        return if (collectTypes(types)) types else null
    }

    /** Adds the types of all handlers; `false` if some handler accepts any update. */
    private fun collectTypes(into: MutableSet<UpdateType>): Boolean {
        for (entry in entries) {
            val known = when (entry) {
                is Route -> updateTypeOf(entry.type)?.let { into += it } != null
                is Child -> entry.router.collectTypes(into)
            }
            if (!known) return false
        }
        return true
    }

    // ------------------------------------------------------------ dispatch

    /** The first matching handler for the update of [dispatch], wrapped with middleware; `null` if none. */
    internal fun match(dispatch: Dispatch): Matched? {
        for (entry in entries) {
            val matched = when (entry) {
                is Route -> if (entry.type.isInstance(dispatch.update)) entry.prepare(dispatch) else null
                is Child -> entry.router.match(dispatch)
            } ?: continue
            return wrap(matched)
        }
        return null
    }

    private fun wrap(matched: Matched): Matched {
        if (middlewares.isEmpty()) return matched
        var run = matched.run
        for (middleware in middlewares.toList().asReversed()) {
            val next = run
            run = { middleware(matched.context, next) }
        }
        return Matched(matched.context, run)
    }

    private fun <U : Update, C : UpdateContext<U>> route(
        type: Class<U>,
        contextType: Class<*>,
        context: (U, Dispatch) -> C,
        filter: (C) -> Boolean,
        handler: suspend C.() -> Unit,
        command: BotCommand? = null,
    ): R {
        entries += Route(type, command) { dispatch ->
            // Contexts are created once per update and context type, so filters and handlers share them.
            @Suppress("UNCHECKED_CAST")
            val ctx = dispatch.contexts.getOrPut(contextType) { context(type.cast(dispatch.update), dispatch) } as C
            if (filter(ctx)) Matched(ctx) { ctx.handler() } else null
        }
        return self
    }

    // runInterruptible: a Java handler cancelled by BotConfig.handlerTimeout or close() is interrupted.
    private fun <C> Handler<C>.toSuspend(): suspend C.() -> Unit = {
        val ctx = this
        runInterruptible(Dispatchers.IO) { handle(ctx) }
    }
}

/**
 * A group of handlers with its own middleware, added to a bot (or another router) with
 * [BaseRouter.include]. Split a large bot into feature modules:
 *
 * ```kotlin
 * val admin = Router().apply {
 *     use { ctx, next -> if (ctx.user?.userId in admins) next() }
 *     onCommand("ban") { ... }
 * }
 * bot.include(admin)
 * ```
 *
 * ```java
 * Router admin = new Router()
 *         .use((ctx, next) -> { if (isAdmin(ctx.getUser())) next.proceed(); })
 *         .onCommand("ban", ctx -> ...);
 * bot.include(admin);
 * ```
 */
public class Router : BaseRouter<Router>()

/** Everything known about one update while it is dispatched; shared by all contexts created for it. */
internal class Dispatch(
    val bot: MaxBot,
    val update: Update,
    val stateKey: StateKey?,
    @Volatile var dialog: DialogState?,
) {
    val contexts = HashMap<Class<*>, UpdateContext<*>>(2)
}

/** A matched handler: its context and the action that runs it with middleware. */
internal class Matched(val context: UpdateContext<*>, val run: suspend () -> Unit)
