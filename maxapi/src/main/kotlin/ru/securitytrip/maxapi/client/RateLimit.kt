package ru.securitytrip.maxapi.client

import kotlinx.coroutines.delay

/**
 * Client-side limit on the request rate, set with [MaxClientConfig.rateLimit]. Requests over the
 * limit wait (suspending, not blocking a thread) instead of being rejected by the server with 429.
 *
 * The limit is a token bucket: up to [burst] requests go at once, then [requestsPerSecond] on
 * average. With [perChatRequestsPerSecond] set, requests that address a chat (by `chat_id`, a
 * `/chats/{id}` path, or as an answer to a button press there) are also limited per chat, which keeps one busy chat from using the whole
 * budget. With [perChatAnswersPerSecond] set, answers to button presses (`POST /answers`) are
 * limited per chat as well. Retries count as requests.
 *
 * [MAX] follows the limits of Max itself:
 *
 * ```kotlin
 * MaxClientConfig(token, rateLimit = RateLimit.MAX)
 * MaxClientConfig(token, rateLimit = RateLimit(requestsPerSecond = 25.0, perChatRequestsPerSecond = 1.0))
 * ```
 *
 * The limiter belongs to one client, so several instances of a bot with the same token each have
 * their own budget.
 */
public class RateLimit private constructor(
    public val requestsPerSecond: Double,
    public val burst: Int,
    public val perChatRequestsPerSecond: Double?,
    public val perChatBurst: Int,
    /**
     * Answers to button presses per second in one chat; `null` does not limit them separately.
     *
     * `POST /answers` carries only the `callback_id`, so the client learns the chat from the
     * `message_callback` updates that [ru.securitytrip.maxapi.bot.MaxBot] dispatches: answers given
     * from its handlers (with `CallbackContext.answer` or `MaxClient.answerOnCallback`) are limited,
     * while answers to presses the bot has not dispatched, or whose message was deleted, are not.
     */
    public val perChatAnswersPerSecond: Double?,
    /** How many answers to button presses in one chat may go at once. */
    public val perChatAnswersBurst: Int,
) {
    @JvmOverloads
    public constructor(
        requestsPerSecond: Double,
        burst: Int = maxOf(1, requestsPerSecond.toInt()),
        perChatRequestsPerSecond: Double? = null,
        perChatBurst: Int = 1,
    ) : this(requestsPerSecond, burst, perChatRequestsPerSecond, perChatBurst, null, 1)

    init {
        require(requestsPerSecond > 0) { "requestsPerSecond must be > 0" }
        require(burst >= 1) { "burst must be >= 1" }
        require(perChatRequestsPerSecond == null || perChatRequestsPerSecond > 0) { "perChatRequestsPerSecond must be > 0" }
        require(perChatBurst >= 1) { "perChatBurst must be >= 1" }
        require(perChatAnswersPerSecond == null || perChatAnswersPerSecond > 0) { "perChatAnswersPerSecond must be > 0" }
        require(perChatAnswersBurst >= 1) { "perChatAnswersBurst must be >= 1" }
    }

    /**
     * A copy of this limit that also limits answers to button presses to [perSecond] per chat,
     * with up to [burst] at once; `null` removes that limit. See [perChatAnswersPerSecond].
     */
    @JvmOverloads
    public fun withPerChatAnswers(perSecond: Double?, burst: Int = 1): RateLimit =
        RateLimit(requestsPerSecond, this.burst, perChatRequestsPerSecond, perChatBurst, perSecond, burst)

    override fun toString(): String =
        "RateLimit(requestsPerSecond=$requestsPerSecond, burst=$burst, " +
            "perChatRequestsPerSecond=$perChatRequestsPerSecond, perChatBurst=$perChatBurst" +
            (if (perChatAnswersPerSecond == null) "" else ", perChatAnswersPerSecond=$perChatAnswersPerSecond, perChatAnswersBurst=$perChatAnswersBurst") +
            ")"

    public companion object {
        /**
         * The limits of Max: 30 requests per second to the API and 2 answers to button presses per
         * second in one chat. Requests are spread evenly (a burst of 1), so no second holds more
         * requests than the server allows.
         */
        @JvmField
        public val MAX: RateLimit = RateLimit(requestsPerSecond = 30.0, burst = 1).withPerChatAnswers(2.0)
    }
}

/** A token bucket that hands out reservations: callers over the limit wait for their turn. */
internal class TokenBucket(
    private val ratePerSecond: Double,
    private val capacity: Int,
    private val nanoTime: () -> Long,
) {
    private var tokens = capacity.toDouble()
    private var updated = nanoTime()

    /** Takes one token, waiting until it is available. */
    suspend fun acquire() {
        val waitNanos = reserve()
        if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
    }

    /** Takes one token now, possibly going into debt; returns how long to wait until it is paid off. */
    @Synchronized
    fun reserve(): Long {
        val now = nanoTime()
        tokens = minOf(capacity.toDouble(), tokens + (now - updated) / 1e9 * ratePerSecond)
        updated = now
        tokens -= 1
        return if (tokens >= 0) 0 else (-tokens / ratePerSecond * 1e9).toLong()
    }
}

/** The global bucket and the per-chat buckets of a [RateLimit]. */
internal class RateLimiter(private val limit: RateLimit, private val nanoTime: () -> Long = System::nanoTime) {
    private val global = TokenBucket(limit.requestsPerSecond, limit.burst, nanoTime)

    /** Per-chat buckets of the most recently used chats; idle chats are forgotten. */
    private val chats = BucketMap()

    /** Per-chat buckets of answers to button presses. */
    private val answers = BucketMap()

    /**
     * Waits until a request may go. [chatId] is the chat the request addresses, if known;
     * [answer] tells that the request answers a button press in that chat.
     */
    suspend fun acquire(chatId: Long?, answer: Boolean = false) {
        if (chatId != null) {
            limit.perChatRequestsPerSecond?.let { chats.bucket(chatId, it, limit.perChatBurst).acquire() }
            if (answer) limit.perChatAnswersPerSecond?.let { answers.bucket(chatId, it, limit.perChatAnswersBurst).acquire() }
        }
        global.acquire()
    }

    internal val trackedChats: Int get() = chats.size

    internal val trackedAnswerChats: Int get() = answers.size

    private inner class BucketMap {
        private val buckets = object : LinkedHashMap<Long, TokenBucket>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TokenBucket>): Boolean = size > MAX_CHATS
        }

        val size: Int get() = synchronized(buckets) { buckets.size }

        fun bucket(chatId: Long, ratePerSecond: Double, capacity: Int): TokenBucket =
            synchronized(buckets) { buckets.getOrPut(chatId) { TokenBucket(ratePerSecond, capacity, nanoTime) } }
    }

    internal companion object {
        const val MAX_CHATS = 10_000
    }
}

/**
 * Chats of recently dispatched button presses by `callback_id`, because `POST /answers` does not
 * name the chat. Filled by [ru.securitytrip.maxapi.bot.MaxBot]; old presses are forgotten.
 */
internal class CallbackChats {
    private val chats = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_CALLBACKS
    }

    fun remember(callbackId: String, chatId: Long): Unit = synchronized(chats) { chats[callbackId] = chatId }

    fun chatOf(callbackId: String?): Long? = callbackId?.let { synchronized(chats) { chats[it] } }

    internal val size: Int get() = synchronized(chats) { chats.size }

    internal companion object {
        const val MAX_CALLBACKS = 10_000
    }
}
