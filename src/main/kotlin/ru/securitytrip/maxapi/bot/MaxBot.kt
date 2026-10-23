package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxException
import ru.securitytrip.maxapi.client.UnauthorizedException
import ru.securitytrip.maxapi.model.SimpleQueryResult
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.update.updateTypeOf
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Predicate
import kotlin.math.min

/**
 * A bot: a set of update handlers plus the machinery that delivers updates to them,
 * either by long polling or from a webhook.
 *
 * ```kotlin
 * val bot = MaxBot(token)
 * bot.onCommand("start") { reply("Hello, ${user?.firstName}!") }
 * bot.onCallback("like") { answer("Thanks!") }
 * bot.onMessage { replyToMessage(text ?: "I only understand text") }
 * bot.startPolling()
 * ```
 *
 * ```java
 * MaxBot bot = new MaxBot(token);
 * bot.onCommand("start", ctx -> ctx.replyBlocking("Hello!"));
 * bot.startBlocking();
 * ```
 *
 * ## Routing
 *
 * Handlers are checked in registration order and **only the first matching one runs**.
 * Register specific handlers (commands, exact payloads) before catch-all ones like `onMessage { }`.
 * Updates no handler matches are ignored.
 *
 * Every registration method has two overloads: a Kotlin `suspend` lambda with the context as
 * receiver, and a Java [Handler] that runs on `Dispatchers.IO`. Registration methods return the
 * bot, so calls can be chained.
 *
 * ## Errors
 *
 * Exceptions thrown by handlers are passed to [onError] (by default they are logged with
 * `System.Logger`) and never stop the bot.
 *
 * ## Delivery
 *
 * - Long polling: [startPolling] (Kotlin), [start] (background), [startBlocking] (Java).
 *   Only the update types the bot has handlers for are requested.
 * - Webhook: subscribe with [setWebhook], then pass request bodies to [webhookHandler] from your HTTP server.
 * - Custom: pass updates to [processUpdate] yourself, e.g. in tests.
 *
 * Register all handlers before starting delivery.
 *
 * @property client client used by the bot and its handlers.
 * @property config bot settings.
 */
public class MaxBot private constructor(
    public val client: MaxClient,
    public val config: BotConfig,
    private val ownsClient: Boolean,
) : Closeable {
    /** A bot that uses an existing `client`. [close] does not close the client. */
    @JvmOverloads
    public constructor(client: MaxClient, config: BotConfig = BotConfig()) : this(client, config, false)

    /** A bot with its own client for `token`. [close] closes that client too. */
    @JvmOverloads
    public constructor(token: String, config: BotConfig = BotConfig()) : this(MaxClient(token), config, true)

    /**
     * A route returns a ready-to-run action for a matching update, or `null` otherwise.
     * Contexts are created once per update and context type and shared through `contexts`.
     */
    private class Route(
        val type: Class<out Update>,
        val prepare: (update: Update, contexts: MutableMap<Class<*>, Any>) -> (suspend () -> Unit)?,
    )

    private val routes = CopyOnWriteArrayList<Route>()

    @Volatile
    private var errorHandler: suspend (Throwable, Update?) -> Unit = { error, update ->
        log.log(System.Logger.Level.ERROR, "Error while handling ${if (update == null) "updates" else update.updateType}", error)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("max-bot"))

    @Volatile
    private var pollingJob: Job? = null

    @Volatile
    private var pollingFailure: Throwable? = null

    /** The job requesting updates; cancelling it stops polling but lets workers drain their queues. */
    @Volatile
    private var poller: Job? = null

    @Volatile
    private var stopRequested = false

    /**
     * Username of the bot, without `@`. Commands with a suffix (`/start@name`) are handled only when
     * the suffix matches it. If not set, it is fetched with `getMyInfo` the first time such a command
     * arrives.
     */
    @Volatile
    public var username: String? = null

    // ------------------------------------------------------------ messages

    /** Handles every new message that earlier handlers did not take. */
    public fun onMessage(handler: suspend MessageContext.() -> Unit): MaxBot =
        onMessage({ true }, handler)

    /** Java version of [onMessage]. */
    public fun onMessage(handler: Handler<MessageContext>): MaxBot = onMessage({ true }, handler.toSuspend())

    /**
     * Handles new messages matching [filter]:
     *
     * ```kotlin
     * bot.onMessage({ it.text?.contains("price", ignoreCase = true) == true }) { reply("See our catalog") }
     * ```
     */
    public fun onMessage(filter: (MessageContext) -> Boolean, handler: suspend MessageContext.() -> Unit): MaxBot =
        route(MessageCreatedUpdate::class.java, MessageContext::class.java, { MessageContext(it, this) }, filter, handler)

    /** Java version of [onMessage] with a filter. */
    public fun onMessage(filter: Predicate<MessageContext>, handler: Handler<MessageContext>): MaxBot =
        onMessage(filter::test, handler.toSuspend())

    /**
     * Handles the command `/name`. Matching ignores case, so `onCommand("start")` handles `/start` and
     * `/Start`. A command with a suffix, like `/start@my_bot`, is handled only if the suffix is this
     * bot's [username]. Arguments after the command are available as [MessageContext.commandArgs].
     *
     * @param name command name, with or without the leading `/`.
     */
    public fun onCommand(name: String, handler: suspend MessageContext.() -> Unit): MaxBot {
        val command = name.removePrefix("/").lowercase()
        require(command.isNotBlank()) { "Command name must not be blank" }
        return onMessage({ it.command == command }, handler)
    }

    /** Java version of [onCommand]. */
    public fun onCommand(name: String, handler: Handler<MessageContext>): MaxBot =
        onCommand(name, handler.toSuspend())

    // ----------------------------------------------------------- callbacks

    /** Handles every callback button press that earlier handlers did not take. */
    public fun onCallback(handler: suspend CallbackContext.() -> Unit): MaxBot = onCallback({ true }, handler)

    /** Java version of [onCallback]. */
    public fun onCallback(handler: Handler<CallbackContext>): MaxBot = onCallback({ true }, handler.toSuspend())

    /** Handles presses of buttons whose payload equals [payload]. */
    public fun onCallback(payload: String, handler: suspend CallbackContext.() -> Unit): MaxBot =
        onCallback({ it.payload == payload }, handler)

    /** Java version of [onCallback] with a payload. */
    public fun onCallback(payload: String, handler: Handler<CallbackContext>): MaxBot =
        onCallback(payload, handler.toSuspend())

    /**
     * Handles button presses matching [filter], e.g. a payload prefix:
     *
     * ```kotlin
     * bot.onCallback({ it.payload?.startsWith("buy:") == true }) { ... }
     * ```
     */
    public fun onCallback(filter: (CallbackContext) -> Boolean, handler: suspend CallbackContext.() -> Unit): MaxBot =
        route(MessageCallbackUpdate::class.java, CallbackContext::class.java, { CallbackContext(it, this) }, filter, handler)

    /** Java version of [onCallback] with a filter. */
    public fun onCallback(filter: Predicate<CallbackContext>, handler: Handler<CallbackContext>): MaxBot =
        onCallback(filter::test, handler.toSuspend())

    // ------------------------------------------------------- other updates

    /** Handles a user starting the bot, including via a deep link (see [BotStartedContext.payload]). */
    public fun onBotStarted(handler: suspend BotStartedContext.() -> Unit): MaxBot =
        route(BotStartedUpdate::class.java, BotStartedContext::class.java, { BotStartedContext(it, this) }, { true }, handler)

    /** Java version of [onBotStarted]. */
    public fun onBotStarted(handler: Handler<BotStartedContext>): MaxBot = onBotStarted(handler.toSuspend())

    /**
     * Handles every update of [type] or its subclasses. Pass `Update::class.java` to handle all updates.
     *
     * ```java
     * bot.onUpdate(BotAddedToChatUpdate.class, ctx -> ctx.replyBlocking("Hi everyone!"));
     * ```
     */
    public fun <U : Update> onUpdate(type: Class<U>, handler: suspend UpdateContext<U>.() -> Unit): MaxBot =
        route(type, UpdateContext::class.java, { UpdateContext(it, this) }, { true }, handler)

    /** Java version of [onUpdate]. */
    public fun <U : Update> onUpdate(type: Class<U>, handler: Handler<UpdateContext<U>>): MaxBot =
        onUpdate(type, handler.toSuspend())

    /**
     * Handles every update of type [U]:
     *
     * ```kotlin
     * bot.on<BotAddedToChatUpdate> { reply("Hi everyone!") }
     * bot.on<MessageEditedUpdate> { println("Edited: ${update.message.body?.text}") }
     * ```
     */
    public inline fun <reified U : Update> on(noinline handler: suspend UpdateContext<U>.() -> Unit): MaxBot =
        onUpdate(U::class.java, handler)

    /**
     * Sets the error handler, replacing the default one that logs to `System.Logger`
     * (logger name `ru.securitytrip.maxapi.bot`).
     *
     * It receives exceptions thrown by update handlers together with the update, and long polling
     * errors with `update = null`. Exceptions thrown by the error handler itself are logged.
     */
    public fun onError(handler: suspend (error: Throwable, update: Update?) -> Unit): MaxBot = apply {
        errorHandler = handler
    }

    /** Java version of [onError]. */
    public fun onError(handler: ErrorHandler): MaxBot = onError { error, update -> handler.handle(error, update) }

    /**
     * Update types the registered handlers can process, or `null` if some handler accepts any
     * update (e.g. `onUpdate(Update::class.java)`). Long polling and [setWebhook] use it to request
     * only these types.
     */
    public fun subscribedUpdateTypes(): Set<UpdateType>? {
        val types = LinkedHashSet<UpdateType>()
        for (route in routes) types += updateTypeOf(route.type) ?: return null
        return types
    }

    // ------------------------------------------------------------ dispatch

    /**
     * Runs the first matching handler for [update] and returns when it completes.
     *
     * Exceptions thrown by the handler are passed to [onError] and not rethrown.
     * Use it to feed updates from a custom source or in tests.
     */
    public suspend fun processUpdate(update: Update) {
        try {
            if (username == null && isAddressedCommand(update)) username = client.getMyInfo().username
            val contexts = HashMap<Class<*>, Any>(2)
            for (route in routes) {
                if (!route.type.isInstance(update)) continue
                val action = route.prepare(update, contexts) ?: continue
                action()
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors such as TODO() or AssertionError must not take down the polling workers either.
            reportError(e, update)
        }
    }

    private fun isAddressedCommand(update: Update): Boolean =
        update is MessageCreatedUpdate && MessageContext.parseCommand(update.message.body?.text)?.botName != null

    /** Blocking version of [processUpdate] for Java. */
    public fun processUpdateBlocking(update: Update): Unit = runBlocking { processUpdate(update) }

    // ------------------------------------------------------------- polling

    /**
     * Receives updates by long polling until the calling coroutine is cancelled.
     *
     * Updates are handled by [BotConfig.parallelism] workers; updates of one chat always go to the
     * same worker, so they are handled in order. Workers run in the caller's coroutine context, so
     * with a single-threaded dispatcher they interleave at suspension points instead of running in
     * parallel; use [start] to run on `Dispatchers.Default`. Transient errors are reported to
     * [onError] and retried with exponential backoff.
     *
     * Long polling is limited in speed and retention time; the API recommends webhooks in production.
     *
     * @throws UnauthorizedException if the token is invalid.
     * @throws MaxException if a webhook subscription is active (remove it with `client.unsubscribe(url)`).
     */
    public suspend fun startPolling(): Unit = coroutineScope {
        val queues = List(config.parallelism) { Channel<Update>(config.queueCapacity) }
        for (queue in queues) {
            launch(CoroutineName("max-bot-worker")) {
                for (update in queue) processUpdate(update)
            }
        }
        val polling = launch(CoroutineName("max-bot-poller")) { pollLoop(queues) }
        poller = polling
        if (stopRequested) polling.cancel()
        try {
            polling.join()
        } finally {
            poller = null
            stopRequested = false
            // Workers finish the updates already received (the server considers them delivered),
            // then this scope completes.
            queues.forEach { it.close() }
        }
    }

    /**
     * Starts long polling in the background and returns its job.
     * A fatal error ends the job and is reported to [onError].
     *
     * @throws IllegalStateException if polling is already running.
     */
    public fun start(): Job = synchronized(this) {
        check(pollingJob?.isActive != true) { "Polling is already running" }
        pollingFailure = null
        stopRequested = false
        scope.launch(CoroutineName("max-bot-polling")) {
            try {
                startPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                pollingFailure = e
                reportError(e, null)
            }
        }.also { pollingJob = it }
    }

    /**
     * For Java: starts long polling and blocks the calling thread until [stop] is called
     * from another thread, or until a fatal error happens.
     *
     * @throws UnauthorizedException if the token is invalid.
     * @throws MaxException if a webhook subscription is active.
     */
    public fun startBlocking() {
        val job = start()
        runBlocking { job.join() }
        pollingFailure?.let { throw it }
    }

    /**
     * Stops long polling started with [startPolling], [start] or [startBlocking]: no new updates are
     * requested, while updates already received are still handled. After that, `startPolling` and
     * `startBlocking` return and the job of `start` completes. Use [close] to cancel immediately.
     */
    public fun stop() {
        stopRequested = true
        poller?.cancel()
    }

    // ------------------------------------------------------------- webhook

    /**
     * Returns a handler that turns webhook request bodies into dispatched updates.
     *
     * @param secret the secret passed to [setWebhook]; requests with a different
     *   `X-Max-Bot-Api-Secret` header are rejected. `null` disables the check.
     */
    @JvmOverloads
    public fun webhookHandler(secret: String? = null): WebhookHandler = WebhookHandler(this, secret)

    /**
     * Subscribes the bot to a webhook at [url] for the update types in [subscribedUpdateTypes].
     * After that, long polling is unavailable until the subscription is removed.
     *
     * Register all handlers first: the update types are taken at the time of the call.
     *
     * @param url HTTPS URL of your endpoint; the server must listen on port 443.
     * @param secret value of the `X-Max-Bot-Api-Secret` header Max sends; 5–256 characters of `[A-Za-z0-9_-]`.
     * @throws IllegalStateException if no handlers are registered.
     */
    public suspend fun setWebhook(url: String, secret: String? = null): SimpleQueryResult {
        val types = subscribedUpdateTypes()
        check(types == null || types.isNotEmpty()) { "Register handlers before calling setWebhook" }
        return client.subscribe(url, secret, types)
    }

    /** Blocking version of [setWebhook] for Java. */
    @JvmOverloads
    public fun setWebhookBlocking(url: String, secret: String? = null): SimpleQueryResult =
        runBlocking { setWebhook(url, secret) }

    internal fun launchProcessing(update: Update): Job {
        check(scope.isActive) { "The bot is closed" }
        return scope.launch { processUpdate(update) }
    }

    /**
     * Stops polling, cancels running handlers immediately and closes the client if the bot created it.
     * To let already received updates finish first, call [stop] and wait for polling to end.
     */
    override fun close() {
        stop()
        scope.cancel()
        if (ownsClient) client.close()
    }

    // ---------------------------------------------------------------------

    private fun <U : Update, C : Any> route(
        type: Class<U>,
        contextType: Class<*>,
        context: (U) -> C,
        filter: (C) -> Boolean,
        handler: suspend C.() -> Unit,
    ): MaxBot = apply {
        routes += Route(type) { update, contexts ->
            @Suppress("UNCHECKED_CAST")
            val ctx = contexts.getOrPut(contextType) { context(type.cast(update)) } as C
            if (filter(ctx)) suspend { ctx.handler() } else null
        }
    }

    private fun <C> Handler<C>.toSuspend(): suspend C.() -> Unit = {
        val ctx = this
        withContext(Dispatchers.IO) { handle(ctx) }
    }

    private suspend fun reportError(error: Throwable, update: Update?) {
        try {
            errorHandler(error, update)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Error handler failed", e)
        }
    }

    private suspend fun pollLoop(queues: List<SendChannel<Update>>) {
        val types = subscribedUpdateTypes()
        var marker: Long? = null
        var backoff = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val started = System.nanoTime()
            try {
                val page = client.getUpdates(config.pollLimit, config.pollTimeoutSeconds, marker, types)
                backoff = 0
                for (update in page.updates) queues[shardOf(update, queues.size)].send(update)
                page.marker?.let { marker = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: MaxApiException) {
                if (e.httpStatus == 405) {
                    throw MaxException(
                        "Long polling is not available while a webhook subscription is active; call unsubscribe() first",
                        e,
                    )
                }
                backoff = nextBackoff(backoff)
                reportError(e, null)
                delay(backoff)
            } catch (e: Exception) {
                backoff = nextBackoff(backoff)
                reportError(e, null)
                delay(backoff)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val wait = config.minPollInterval.toMillis() - elapsedMs
            if (wait > 0) delay(wait)
        }
    }

    private fun nextBackoff(current: Long): Long =
        min(if (current == 0L) 1_000 else current * 2, config.maxErrorBackoff.toMillis())

    private fun shardOf(update: Update, shards: Int): Int {
        val key = update.chatId ?: update.user?.userId ?: 0L
        return Math.floorMod(key, shards.toLong()).toInt()
    }

    private companion object {
        val log: System.Logger = System.getLogger("ru.securitytrip.maxapi.bot")
    }
}
