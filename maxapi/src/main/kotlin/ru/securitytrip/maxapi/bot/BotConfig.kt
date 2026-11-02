package ru.securitytrip.maxapi.bot

import ru.securitytrip.maxapi.events.MaxEventListener
import java.time.Duration

/**
 * Settings of a [MaxBot].
 *
 * ```kotlin
 * val bot = MaxBot(token, BotConfig(handlerTimeout = Duration.ofMinutes(5)))
 * ```
 *
 * ```java
 * MaxBot bot = new MaxBot(token, BotConfig.builder().handlerTimeout(Duration.ofMinutes(5)).build());
 * ```
 */
public class BotConfig(
    /**
     * Most handlers running at once, for long polling and for webhook updates handled in the background.
     * Every chat has its own queue, so updates of one chat are handled one at a time and in order,
     * while different chats are handled in parallel and a stuck handler holds up only its own chat.
     */
    public val parallelism: Int = DEFAULT_PARALLELISM,
    /**
     * Most updates waiting in the queue of one chat. Further updates of that chat are dropped (long
     * polling, see [QueueOverflowException]) or refused (webhook, so that Max redelivers them). Long
     * polling waits when all chats together have `parallelism * queueCapacity` updates waiting.
     */
    public val queueCapacity: Int = 64,
    /** How long one `getUpdates` request waits for updates, 0–90 seconds. */
    public val pollTimeoutSeconds: Int = 30,
    /** Maximum number of updates per `getUpdates` request, 1–1000. */
    public val pollLimit: Int = 100,
    /** Minimum interval between `getUpdates` requests. The API requires at least 300 ms. */
    public val minPollInterval: Duration = Duration.ofMillis(300),
    /** Upper bound of the pause after failed `getUpdates` requests; the pause doubles from 1 second. */
    public val maxErrorBackoff: Duration = Duration.ofSeconds(30),
    /** Where dialog states ([UpdateContext.state]) are kept; by default in memory. */
    public val stateStorage: StateStorage = InMemoryStateStorage(),
    /**
     * Longest time a handler (with its middleware) may run, one minute by default. A handler that takes
     * longer is cancelled (Java handlers are interrupted) and [HandlerTimeoutException] goes to `onError`,
     * so a stuck handler does not block its chat forever. `null` means no limit.
     */
    public val handlerTimeout: Duration? = DEFAULT_HANDLER_TIMEOUT,
    /**
     * Drops repeated deliveries of the same update, e.g. [InMemoryUpdateDeduplicator]; `null` handles
     * every delivery. If it also implements [SuspendUpdateDeduplicator], the bot calls the non-blocking method.
     */
    public val deduplicator: UpdateDeduplicator? = null,
    /** Keeps the long polling marker across restarts; `null` (default) does not keep it. */
    public val markerStore: MarkerStore? = null,
    /** Receives an event for every dispatched update, e.g. for metrics. */
    public val eventListener: MaxEventListener = MaxEventListener.NONE,
    /** Where the bot remembers the chats it is in, read with [MaxBot.chats]; `null` (default) does not remember them. */
    public val chatStore: ChatStore? = null,
    /**
     * Non-blocking alternative to [deduplicator] for stores with an asynchronous client; set one of the
     * two. `null` (default) leaves deduplication to [deduplicator].
     */
    public val suspendDeduplicator: SuspendUpdateDeduplicator? = null,
    /**
     * Whether the bot answers a button press that its handlers left unanswered (no handler matched, the
     * handler failed or did not call `answer`), with an empty answer. Until a press is answered, the
     * client of the user shows it as loading.
     */
    public val autoAnswerCallbacks: Boolean = true,
) {
    /** The constructor of 1.4.0, kept for binary compatibility. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public constructor(
        parallelism: Int = DEFAULT_PARALLELISM,
        queueCapacity: Int = 64,
        pollTimeoutSeconds: Int = 30,
        pollLimit: Int = 100,
        minPollInterval: Duration = Duration.ofMillis(300),
        maxErrorBackoff: Duration = Duration.ofSeconds(30),
        stateStorage: StateStorage = InMemoryStateStorage(),
        handlerTimeout: Duration? = DEFAULT_HANDLER_TIMEOUT,
        deduplicator: UpdateDeduplicator? = null,
        markerStore: MarkerStore? = null,
        eventListener: MaxEventListener = MaxEventListener.NONE,
        chatStore: ChatStore? = null,
        suspendDeduplicator: SuspendUpdateDeduplicator? = null,
    ) : this(
        parallelism, queueCapacity, pollTimeoutSeconds, pollLimit, minPollInterval, maxErrorBackoff,
        stateStorage, handlerTimeout, deduplicator, markerStore, eventListener, chatStore, suspendDeduplicator, true,
    )

    /** A constructor without [suspendDeduplicator], hidden in 1.4.0 as well; kept for binary compatibility. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public constructor(
        parallelism: Int = DEFAULT_PARALLELISM,
        queueCapacity: Int = 64,
        pollTimeoutSeconds: Int = 30,
        pollLimit: Int = 100,
        minPollInterval: Duration = Duration.ofMillis(300),
        maxErrorBackoff: Duration = Duration.ofSeconds(30),
        stateStorage: StateStorage = InMemoryStateStorage(),
        handlerTimeout: Duration? = DEFAULT_HANDLER_TIMEOUT,
        deduplicator: UpdateDeduplicator? = null,
        markerStore: MarkerStore? = null,
        eventListener: MaxEventListener = MaxEventListener.NONE,
        chatStore: ChatStore? = null,
    ) : this(
        parallelism, queueCapacity, pollTimeoutSeconds, pollLimit, minPollInterval, maxErrorBackoff,
        stateStorage, handlerTimeout, deduplicator, markerStore, eventListener, chatStore, null, true,
    )

    /** The constructor of 1.3, kept for binary compatibility. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public constructor(
        parallelism: Int = DEFAULT_PARALLELISM,
        queueCapacity: Int = 64,
        pollTimeoutSeconds: Int = 30,
        pollLimit: Int = 100,
        minPollInterval: Duration = Duration.ofMillis(300),
        maxErrorBackoff: Duration = Duration.ofSeconds(30),
        stateStorage: StateStorage = InMemoryStateStorage(),
        handlerTimeout: Duration? = DEFAULT_HANDLER_TIMEOUT,
        deduplicator: UpdateDeduplicator? = null,
        markerStore: MarkerStore? = null,
        eventListener: MaxEventListener = MaxEventListener.NONE,
    ) : this(
        parallelism, queueCapacity, pollTimeoutSeconds, pollLimit, minPollInterval, maxErrorBackoff,
        stateStorage, handlerTimeout, deduplicator, markerStore, eventListener, null, null, true,
    )

    init {
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(queueCapacity >= 1) { "queueCapacity must be >= 1" }
        require(pollTimeoutSeconds in 0..90) { "pollTimeoutSeconds must be in 0..90" }
        require(pollLimit in 1..1000) { "pollLimit must be in 1..1000" }
        require(handlerTimeout == null || handlerTimeout > Duration.ZERO) { "handlerTimeout must be positive" }
        require(deduplicator == null || suspendDeduplicator == null) { "Set deduplicator or suspendDeduplicator, not both" }
    }

    /** Builder for Java. Every setter has the same meaning as the matching constructor parameter. */
    public class Builder {
        private var parallelism = DEFAULT_PARALLELISM
        private var queueCapacity = 64
        private var pollTimeoutSeconds = 30
        private var pollLimit = 100
        private var minPollInterval = Duration.ofMillis(300)
        private var maxErrorBackoff = Duration.ofSeconds(30)
        private var stateStorage: StateStorage? = null
        private var handlerTimeout: Duration? = DEFAULT_HANDLER_TIMEOUT
        private var deduplicator: UpdateDeduplicator? = null
        private var markerStore: MarkerStore? = null
        private var eventListener = MaxEventListener.NONE
        private var chatStore: ChatStore? = null
        private var suspendDeduplicator: SuspendUpdateDeduplicator? = null
        private var autoAnswerCallbacks = true

        public fun parallelism(value: Int): Builder = apply { parallelism = value }
        public fun queueCapacity(value: Int): Builder = apply { queueCapacity = value }
        public fun pollTimeoutSeconds(value: Int): Builder = apply { pollTimeoutSeconds = value }
        public fun pollLimit(value: Int): Builder = apply { pollLimit = value }
        public fun minPollInterval(value: Duration): Builder = apply { minPollInterval = value }
        public fun maxErrorBackoff(value: Duration): Builder = apply { maxErrorBackoff = value }
        public fun stateStorage(value: StateStorage): Builder = apply { stateStorage = value }
        public fun handlerTimeout(value: Duration?): Builder = apply { handlerTimeout = value }
        public fun deduplicator(value: UpdateDeduplicator?): Builder = apply { deduplicator = value }
        public fun markerStore(value: MarkerStore?): Builder = apply { markerStore = value }
        public fun eventListener(value: MaxEventListener): Builder = apply { eventListener = value }
        public fun chatStore(value: ChatStore?): Builder = apply { chatStore = value }
        public fun suspendDeduplicator(value: SuspendUpdateDeduplicator?): Builder = apply { suspendDeduplicator = value }
        public fun autoAnswerCallbacks(value: Boolean): Builder = apply { autoAnswerCallbacks = value }

        public fun build(): BotConfig = BotConfig(
            parallelism, queueCapacity, pollTimeoutSeconds, pollLimit, minPollInterval, maxErrorBackoff,
            stateStorage ?: InMemoryStateStorage(),
            handlerTimeout, deduplicator, markerStore, eventListener, chatStore, suspendDeduplicator, autoAnswerCallbacks,
        )
    }

    public companion object {
        /** Default of [parallelism]. */
        public const val DEFAULT_PARALLELISM: Int = 64

        /** Default of [handlerTimeout]: one minute. */
        @JvmField
        public val DEFAULT_HANDLER_TIMEOUT: Duration = Duration.ofMinutes(1)

        /** Starts building a config for Java code. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
