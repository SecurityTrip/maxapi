package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.events.WebhookResubscribeEvent
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
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.user.BotCommandsInfo
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update
import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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

    /** [BotConfig.parallelism] for all handlers of the bot, from long polling and webhooks alike. */
    private val handlerSlots = Semaphore(config.parallelism)

    @Volatile
    private var pollingJob: Job? = null

    /**
     * The error that ended long polling started with [start] or [startBlocking], e.g. an invalid token;
     * `null` while polling runs, after it stopped normally and before it started. Transient errors do
     * not end polling: they are retried and only reported to [onError].
     */
    @Volatile
    public var pollingFailure: Throwable? = null
        private set

    /** The job requesting updates; cancelling it stops polling but lets workers drain their queues. */
    @Volatile
    private var poller: Job? = null

    @Volatile
    private var stopRequested = false

    private val chatTracker = config.chatStore?.let { ChatTracker(it, client) }

    init {
        if (config.autoAnswerCallbacks) client.transport.answeredCallbacks.enabled = true
    }

    /**
     * Username of the bot, without `@`. Commands with a suffix (`/start@name`) are handled only when
     * the suffix matches it. If not set, it is fetched with `getMyInfo` the first time such a command
     * arrives; set it at startup (the Spring Boot starter does) so that a failure of that request
     * cannot affect the command.
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
        val context = updateContext(update)
        if (context === EmptyCoroutineContext) dispatch(update) else withContext(context) { dispatch(update) }
    }

    /** [MaxEventListener.updateContext] of [BotConfig.eventListener]. */
    private fun updateContext(update: Update): CoroutineContext {
        val listener = config.eventListener
        var context: CoroutineContext = EmptyCoroutineContext
        if (listener !== MaxEventListener.NONE) listener.safely { context = updateContext(update) }
        return context
    }

    private suspend fun isNew(update: Update): Boolean {
        config.suspendDeduplicator?.let { return it.markSeen(update) }
        val deduplicator = config.deduplicator ?: return true
        return if (deduplicator is SuspendUpdateDeduplicator) deduplicator.markSeen(update) else deduplicator.isNew(update)
    }

    private suspend fun dispatch(update: Update) {
        val started = System.nanoTime()
        if (!isNew(update)) {
            updateEvent(update, false, started, null)
            return
        }
        trackChat(update)
        var handled = false
        var error: Throwable? = null
        try {
            rememberCallbackChat(update)
            if (username == null && isAddressedCommand(update)) fetchUsername(update)
            val key = StateKey.of(update)
            val dispatch = Dispatch(this, update, key, key?.let { config.stateStorage.get(it) })
            val matched = match(dispatch)
            if (matched != null) {
                handled = true
                run(matched, update)
            }
        } catch (e: CancellationException) {
            // A cancellation of the handler's own, e.g. a timeout of its withTimeout, is its error like
            // any other; only the cancellation of this coroutine ends the dispatch.
            currentCoroutineContext().ensureActive()
            error = e
        } catch (e: Throwable) {
            // Errors such as TODO() or AssertionError must not take down the polling workers either.
            error = e
        }
        updateEvent(update, handled, started, error)
        if (error != null) reportError(error, update)
        if (update is MessageCallbackUpdate) autoAnswer(update)
    }

    /**
     * Fills [username] for a command addressed to a bot. If `getMyInfo` fails, the error is reported and
     * the update is still dispatched; its command, of an unknown bot, matches no command handler.
     */
    private suspend fun fetchUsername(update: Update) {
        try {
            username = client.getMyInfo().username
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, update)
        }
    }

    /** Answers a button press nobody answered, so that the client of the user stops waiting; see [BotConfig.autoAnswerCallbacks]. */
    private suspend fun autoAnswer(update: MessageCallbackUpdate) {
        if (!config.autoAnswerCallbacks) return
        val callbackId = update.callback.callbackId
        val answered = client.transport.answeredCallbacks
        if (answered.remove(callbackId)) return
        try {
            client.answerOnCallback(callbackId, CallbackAnswer())
            answered.remove(callbackId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(System.Logger.Level.DEBUG, "Cannot answer the button press $callbackId: ${e.message}")
        }
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

    /** Updates [BotConfig.chatStore]; a failure is reported and does not keep handlers from running. */
    private suspend fun trackChat(update: Update) {
        val tracker = chatTracker ?: return
        try {
            tracker.track(update)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, update)
        }
    }

    /** `POST /answers` does not name the chat, so the per-chat limit of answers learns it here. */
    private fun rememberCallbackChat(update: Update) {
        if (update !is MessageCallbackUpdate || client.config.rateLimit?.perChatAnswersPerSecond == null) return
        update.chatId?.let { client.transport.callbackChats.remember(update.callback.callbackId, it) }
    }

    private fun isAddressedCommand(update: Update): Boolean =
        update is MessageCreatedUpdate && MessageContext.parseCommand(update.message.body?.text)?.botName != null

    /** Blocking version of [processUpdate] for Java. */
    public fun processUpdateBlocking(update: Update): Unit = runBlocking { processUpdate(update) }

    // ------------------------------------------------------------- polling

    /**
     * Whether long polling is running: it was started with [startPolling], [start] or [startBlocking]
     * and has not ended yet. It stays `true` after [stop] until the updates already received are
     * handled. When polling ended with an error, [pollingFailure] holds it.
     */
    public val isPolling: Boolean
        get() = pollingJob?.isActive == true || poller?.isActive == true

    /**
     * Receives updates by long polling until the calling coroutine is cancelled.
     *
     * Every chat has its own queue: updates of a chat are handled one at a time and in order, while
     * different chats are handled in parallel, up to [BotConfig.parallelism] handlers at once. A slow
     * or stuck handler holds up only its own chat. When a chat has [BotConfig.queueCapacity] updates
     * waiting, further updates of that chat are dropped and reported to [onError]; with a
     * [BotConfig.markerStore] the saved marker then stays before the dropped update, so that it is
     * received again after a restart. Polling waits only when all chats together have
     * `parallelism * queueCapacity` updates waiting.
     *
     * Handlers run in the caller's coroutine context, so with a single-threaded dispatcher they
     * interleave at suspension points instead of running in parallel; use [start] to run on
     * `Dispatchers.Default`. Transient errors are reported to [onError] and retried with exponential backoff.
     *
     * Long polling is limited in speed and retention time; the API recommends webhooks in production.
     *
     * @throws UnauthorizedException if the token is invalid.
     * @throws MaxException if a webhook subscription is active: Max then answers long polling with
     *   empty pages, so the bot would silently receive nothing. Remove it with [deleteWebhook].
     */
    public suspend fun startPolling(): Unit = coroutineScope {
        checkNoWebhook()
        val queues = ChatQueues(this, handlerSlots, config.parallelism, config.queueCapacity, ::processUpdate)
        val store = config.markerStore
        val pages = Channel<Page>(Channel.UNLIMITED)
        if (store != null) {
            // Saves the marker of a page once all its updates, and those of earlier pages, are handled.
            launch(CoroutineName("max-bot-marker")) {
                for (page in pages) {
                    page.done.joinAll()
                    // A dropped update: the marker stays before it, so that it is received again after a restart.
                    if (page.done.any { it.isCancelled }) break
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
            queues.close()
            pages.close()
        }
    }

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
     * @throws MaxException if a webhook subscription is active (see [deleteWebhook]).
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
     *   `X-Max-Bot-Api-Secret` header are rejected. `null` disables the check, so that anyone who knows
     *   the URL can send the bot forged updates, e.g. from another user; a warning is logged then. Without
     *   a secret of your own, use [WebhookHandler.secretFor] here and in [setWebhook].
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
        val types = requestedUpdateTypes()
        check(types == null || types.isNotEmpty()) { "Register handlers before calling setWebhook" }
        return client.subscribe(url, secret, types)
    }

    /**
     * Shows the [commands] registered with descriptions in the bot menu, replacing the commands
     * shown before. Call it after registering handlers, e.g. at startup. The mobile apps of Max
     * show the menu; the web client does not.
     */
    public suspend fun syncCommands(): BotCommandsInfo = client.editMyCommands(commands())

    /** Blocking version of [syncCommands] for Java. */
    public fun syncCommandsBlocking(): BotCommandsInfo = runBlocking { syncCommands() }

    // --------------------------------------------------------------- chats

    /**
     * The chats the bot is in, as [BotConfig.chatStore] remembers them: only those of [types], or all
     * when no type is given.
     *
     * ```kotlin
     * val groups = bot.chats(ChatType.CHAT, ChatType.CHANNEL)
     * val subscribers = bot.chats(ChatType.DIALOG).mapNotNull { it.userId }
     * ```
     *
     * @throws IllegalStateException if no chat store is set.
     */
    public suspend fun chats(vararg types: ChatType): List<KnownChat> {
        val store = checkNotNull(config.chatStore) { "Set BotConfig.chatStore to keep the chats of the bot" }
        val chats = store.list()
        return if (types.isEmpty()) chats else chats.filter { it.type in types }
    }

    /** Blocking version of [chats] for Java. */
    public fun chatsBlocking(vararg types: ChatType): List<KnownChat> = runBlocking { chats(*types) }

    /** Blocking version of [setWebhook] for Java. */
    @JvmOverloads
    public fun setWebhookBlocking(url: String, secret: String? = null): SimpleQueryResult =
        runBlocking { setWebhook(url, secret) }

    /**
     * Removes every webhook subscription of the bot, e.g. before switching to long polling or when
     * a webhook server shuts down, and returns the URLs that were removed.
     */
    public suspend fun deleteWebhook(): List<String> =
        client.getSubscriptions().map { it.url }.onEach { client.unsubscribe(it) }

    /** Blocking version of [deleteWebhook] for Java. */
    public fun deleteWebhookBlocking(): List<String> = runBlocking { deleteWebhook() }

    /**
     * Subscribes to [url] again if the bot is no longer subscribed to it, and does nothing otherwise.
     *
     * Max drops a subscription after 8 hours without successful deliveries, e.g. while the server
     * was down or unreachable; updates then wait until the bot subscribes again. Call this method
     * periodically; the Spring Boot starter does it every `max.bot.webhook.check-interval`.
     * Subscribing to the same URL again is harmless, so several instances may check at once.
     *
     * A renewed subscription is logged and reported to [MaxEventListener.onWebhookResubscribe] of
     * [BotConfig.eventListener], a failed one too.
     *
     * @param url the URL passed to [setWebhook].
     * @param secret the secret passed to [setWebhook].
     * @return `true` if the subscription was missing and the bot subscribed again.
     * @throws MaxException if subscribing again failed or the server answered `success = false`.
     */
    public suspend fun ensureWebhook(url: String, secret: String? = null): Boolean {
        if (client.getSubscriptions().any { it.url == url }) return false
        log.log(System.Logger.Level.WARNING, "The webhook subscription to $url is missing; subscribing again")
        val error = try {
            val result = setWebhook(url, secret)
            if (result.success) null else MaxException("Cannot subscribe to $url again: ${result.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        config.eventListener.safely { onWebhookResubscribe(WebhookResubscribeEvent(url, error)) }
        if (error != null) throw error
        log.log(System.Logger.Level.INFO, "Subscribed to $url again")
        return true
    }

    /** Blocking version of [ensureWebhook] for Java. */
    @JvmOverloads
    public fun ensureWebhookBlocking(url: String, secret: String? = null): Boolean = runBlocking { ensureWebhook(url, secret) }

    /**
     * Fails if the bot is subscribed to a webhook. The check is best effort: if the subscriptions
     * cannot be read, polling starts anyway and its own error handling takes over.
     */
    private suspend fun checkNoWebhook() {
        val urls = try {
            client.getSubscriptions().map { it.url }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            throw e
        } catch (e: Exception) {
            log.log(System.Logger.Level.DEBUG, "Cannot check webhook subscriptions: ${e.message}")
            return
        }
        if (urls.isNotEmpty()) {
            throw MaxException(
                "Long polling receives nothing while the bot is subscribed to a webhook (${urls.joinToString()}); " +
                    "remove the subscription with deleteWebhook() first",
            )
        }
    }

    /** Queues of updates that webhook requests leave to the background, see [WebhookHandler.handleAsync]. */
    private val webhookQueues by lazy { ChatQueues(scope, handlerSlots, config.parallelism, config.queueCapacity, ::processUpdate) }

    /** Queues [update] to be handled in the background, in order with the other updates of its chat. */
    internal fun launchProcessing(update: Update) {
        check(scope.isActive) { "The bot is closed" }
        check(webhookQueues.trySend(update)) {
            "Too many updates are waiting (${config.queueCapacity} in chat ${ChatQueues.keyOf(update)}); try again later"
        }
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

    private suspend fun pollLoop(queues: ChatQueues, pages: SendChannel<Page>?, initialMarker: Long?) {
        val types = requestedUpdateTypes()
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
                    if (!queues.send(update, pages?.let { CompletableDeferred<Unit>().also(done::add) })) {
                        reportError(QueueOverflowException(update, config.queueCapacity), update)
                    }
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
                        "Long polling is not available while a webhook subscription is active; call deleteWebhook() first",
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

    /** The types of [subscribedUpdateTypes] and those [BotConfig.chatStore] needs; `null` for all types. */
    private fun requestedUpdateTypes(): Set<UpdateType>? {
        val types = subscribedUpdateTypes() ?: return null
        return if (chatTracker == null) types else types + ChatTracker.UPDATE_TYPES
    }

    private fun nextBackoff(current: Long): Long =
        min(if (current == 0L) 1_000 else current * 2, config.maxErrorBackoff.toMillis())

    private companion object {
        val log: System.Logger = System.getLogger("ru.securitytrip.maxapi.bot")
    }
}
