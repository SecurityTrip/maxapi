package ru.securitytrip.maxapi.client

import kotlinx.coroutines.delay

/**
 * Client-side limit on the request rate, set with [MaxClientConfig.rateLimit]. Requests over the
 * limit wait (suspending, not blocking a thread) instead of being rejected by the server with 429.
 *
 * The limit is a token bucket: up to [burst] requests go at once, then [requestsPerSecond] on
 * average. With [perChatRequestsPerSecond] set, requests that address a chat (by `chat_id` or a
 * `/chats/{id}` path) are also limited per chat, which keeps one busy chat from using the whole
 * budget. Retries count as requests.
 *
 * ```kotlin
 * MaxClientConfig(token, rateLimit = RateLimit(requestsPerSecond = 25.0, perChatRequestsPerSecond = 1.0))
 * ```
 */
public class RateLimit @JvmOverloads constructor(
    public val requestsPerSecond: Double,
    public val burst: Int = maxOf(1, requestsPerSecond.toInt()),
    public val perChatRequestsPerSecond: Double? = null,
    public val perChatBurst: Int = 1,
) {
    init {
        require(requestsPerSecond > 0) { "requestsPerSecond must be > 0" }
        require(burst >= 1) { "burst must be >= 1" }
        require(perChatRequestsPerSecond == null || perChatRequestsPerSecond > 0) { "perChatRequestsPerSecond must be > 0" }
        require(perChatBurst >= 1) { "perChatBurst must be >= 1" }
    }

    override fun toString(): String =
        "RateLimit(requestsPerSecond=$requestsPerSecond, burst=$burst, " +
            "perChatRequestsPerSecond=$perChatRequestsPerSecond, perChatBurst=$perChatBurst)"
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
    private val chats = object : LinkedHashMap<Long, TokenBucket>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TokenBucket>): Boolean = size > MAX_CHATS
    }

    suspend fun acquire(chatId: Long?) {
        val perChat = limit.perChatRequestsPerSecond
        if (chatId != null && perChat != null) {
            synchronized(chats) { chats.getOrPut(chatId) { TokenBucket(perChat, limit.perChatBurst, nanoTime) } }.acquire()
        }
        global.acquire()
    }

    internal val trackedChats: Int get() = synchronized(chats) { chats.size }

    internal companion object {
        const val MAX_CHATS = 10_000
    }
}
