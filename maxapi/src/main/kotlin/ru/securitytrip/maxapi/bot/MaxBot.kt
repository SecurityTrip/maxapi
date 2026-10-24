package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.events.safely
import java.time.Duration
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
import ru.securitytrip.maxapi.model.user.BotCommandsInfo
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import java.io.Closeable
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
 * Updates no handler matches are ignored. See [BaseRouter] for the registration methods,
 * middleware ([use]) and splitting a bot into [Router]s ([include]).
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
) : BaseRouter<MaxBot>(), Closeable {
    /** A bot that uses an existing `client`. [close] does not close the client. */
    @JvmOverloads
    public constructor(client: MaxClient, config: BotConfig = BotConfig()) : this(client, config, false)

    /** A bot with its own client for `token`. [close] closes that client too. */
    @JvmOverloads
    public constructor(token: String, config: BotConfig = BotConfig()) : this(MaxClient(token), config, true)

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

    // ------------------------------------------------------------ dispatch

    /**
     * Runs the first matching handler for [update] and returns when it completes.
     *
     * Exceptions thrown by the handler are passed to [onError] and not rethrown.
     * Use it to feed updates from a custom source or in tests.
     */
    public suspend fun processUpdate(update: Update) {
        val started = System.nanoTime()
        if (config.deduplicator?.isNew(update) == false) {
            updateEvent(update, false, started, null)
            return
        }
        var handled = false
        try {
            if (username == null && isAddressedCommand(update)) username = client.getMyInfo().username
            val key = StateKey.of(update)
            val dispatch = Dispatch(this, update, key, key?.let { config.stateStorage.get(it) })
            val matched = match(dispatch)
            if (matched != null) {
                handled = true
                run(matched, update)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors such as TODO() or AssertionError must not take down the polling workers either.
            updateEvent(update, handled, started, e)
            reportError(e, update)
            return
        }
        updateEvent(update, handled, started, null)
    }

    private suspend fun run(matched: Matched, update: Update) {
        val timeout = config.handlerTimeout ?: return matched.run()
        withTimeoutOrNull(timeout.toMillis()) { matched.run() } ?: throw HandlerTimeoutException(update, timeout)
    }

    private fun updateEvent(update: Update, handled: Boolean, started: Long, error: Throwable?) {
        val listener = config.eventListener
        if (listener === MaxEventListener.NONE) return
        val duration = Duration.ofNanos(System.nanoTime() - started)
        listener.safely { onUpdate(UpdateEvent(update, handled, duration, error)) }
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
        val queues = List(config.parallelism) { Channel<Delivery>(config.queueCapacity) }
        for (queue in queues) {
            launch(CoroutineName("max-bot-worker")) {
                for (delivery in queue) {
                    processUpdate(delivery.update)
                    delivery.done?.complete(Unit)
                }
            }
        }
        val store = config.markerStore
        val pages = Channel<Page>(Channel.UNLIMITED)
        if (store != null) {
            // Saves the marker of a page once all its updates, and those of earlier pages, are handled.
            launch(CoroutineName("max-bot-marker")) {
                for (page in pages) {
                    page.done.awaitAll()
                    try {
                        store.save(page.marker)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportError(e, null)
                    }
                }
            }
        }
        val initialMarker = store?.load()
        val polling = launch(CoroutineName("max-bot-poller")) { pollLoop(queues, pages.takeIf { store != null }, initialMarker) }
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
            pages.close()
        }
    }

    /** An update on its way to a worker; [done] completes when it is handled, if the marker is kept. */
    private class Delivery(val update: Update, val done: CompletableDeferred<Unit>?)

    /** A received page: [marker] may be saved once all of [done] complete. */
    private class Page(val marker: Long, val done: List<CompletableDeferred<Unit>>)

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

    /**
     * Shows the [commands] registered with descriptions in the bot menu, replacing the commands
     * shown before. Call it after registering handlers, e.g. at startup.
     */
    public suspend fun syncCommands(): BotCommandsInfo = client.editMyCommands(commands())

    /** Blocking version of [syncCommands] for Java. */
    public fun syncCommandsBlocking(): BotCommandsInfo = runBlocking { syncCommands() }

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

    private suspend fun reportError(error: Throwable, update: Update?) {
        try {
            errorHandler(error, update)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Error handler failed", e)
        }
    }

    private suspend fun pollLoop(queues: List<SendChannel<Delivery>>, pages: SendChannel<Page>?, initialMarker: Long?) {
        val types = subscribedUpdateTypes()
        var marker: Long? = initialMarker
        var backoff = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val started = System.nanoTime()
            try {
                val page = client.getUpdates(config.pollLimit, config.pollTimeoutSeconds, marker, types)
                backoff = 0
                val done = ArrayList<CompletableDeferred<Unit>>(page.updates.size)
                for (update in page.updates) {
                    val delivery = Delivery(update, pages?.let { CompletableDeferred<Unit>().also(done::add) })
                    queues[shardOf(update, queues.size)].send(delivery)
                }
                page.marker?.let {
                    marker = it
                    pages?.send(Page(it, done))
                }
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
