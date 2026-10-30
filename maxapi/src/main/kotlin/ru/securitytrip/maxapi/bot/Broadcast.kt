package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import ru.securitytrip.maxapi.client.ForbiddenException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.NotFoundException
import ru.securitytrip.maxapi.client.TokenBucket
import ru.securitytrip.maxapi.client.TooManyRequestsException
import ru.securitytrip.maxapi.model.message.NewMessageBody
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Who a broadcast message goes to: a user (in the dialog with the bot) or a chat.
 *
 * ```kotlin
 * val targets = BroadcastTarget.users(subscriberIds) + bot.chats().map(BroadcastTarget::of)
 * ```
 *
 * @property kind whether [id] is a user or a chat.
 * @property id identifier of the user or the chat.
 */
public data class BroadcastTarget(val kind: Kind, val id: Long) {
    /** Kind of a target. */
    public enum class Kind {
        /** A user, sent to with `sendMessageToUser`. */
        USER,

        /** A chat, sent to with `sendMessageToChat`. */
        CHAT,
    }

    /** The target as a string, `u:<id>` or `c:<id>`, under which stores keep it; [parse] reads it back. */
    val key: String get() = (if (kind == Kind.USER) "u:" else "c:") + id

    override fun toString(): String = key

    public companion object {
        /** The user with [userId]. */
        @JvmStatic
        public fun user(userId: Long): BroadcastTarget = BroadcastTarget(Kind.USER, userId)

        /** The chat with [chatId]. */
        @JvmStatic
        public fun chat(chatId: Long): BroadcastTarget = BroadcastTarget(Kind.CHAT, chatId)

        /** The users with [userIds]. */
        @JvmStatic
        public fun users(userIds: Iterable<Long>): List<BroadcastTarget> = userIds.map(::user)

        /** The chats with [chatIds]. */
        @JvmStatic
        public fun chats(chatIds: Iterable<Long>): List<BroadcastTarget> = chatIds.map(::chat)

        /** A chat remembered by a [ChatStore], see [MaxBot.chats]. */
        @JvmStatic
        public fun of(chat: KnownChat): BroadcastTarget = chat(chat.chatId)

        /**
         * The target of a [key].
         * @throws IllegalArgumentException if [key] is not `u:<id>` or `c:<id>`.
         */
        @JvmStatic
        public fun parse(key: String): BroadcastTarget {
            val id = requireNotNull(key.substring(2.coerceAtMost(key.length)).toLongOrNull()) { "Invalid broadcast target: $key" }
            return when (key.take(2)) {
                "u:" -> user(id)
                "c:" -> chat(id)
                else -> throw IllegalArgumentException("Invalid broadcast target: $key")
            }
        }
    }
}

/** What happened to the message of a broadcast for one target. */
public enum class BroadcastOutcome {
    /** The message was sent. */
    SENT,

    /**
     * The target cannot get messages: the user stopped the bot or the bot is not in the chat (HTTP 403),
     * or the user or the chat does not exist (HTTP 404). Not tried again.
     */
    UNAVAILABLE,

    /** Sending failed for another reason. Tried again when the broadcast runs again. */
    FAILED,
}

/**
 * The message of a broadcast for each target, so it can be personal. Called once per attempt.
 *
 * ```java
 * broadcaster.sendBlocking("news-42", targets, target -> NewMessageBody.builder().text("News!").build());
 * ```
 */
public fun interface BroadcastMessage {
    /** The message for [target]. */
    public fun bodyFor(target: BroadcastTarget): NewMessageBody
}

/**
 * Where a [Broadcaster] keeps the progress of broadcasts: the outcome for every target that was
 * handled. A broadcast that stopped (a restart, a crash, a cancelled coroutine) continues from
 * there when it runs again with the same id.
 *
 * [InMemoryBroadcastStore] keeps the progress only while the process runs; `maxapi-jdbc` and
 * `maxapi-redis` keep it in a database. Implement this interface (Kotlin) or extend
 * [BlockingBroadcastStore] (Java) for other storages.
 */
public interface BroadcastStore {
    /** The outcomes recorded for the broadcast [broadcastId]; empty if it never ran. */
    public suspend fun outcomes(broadcastId: String): Map<BroadcastTarget, BroadcastOutcome>

    /** Records the [outcome] for [target], replacing an earlier one. */
    public suspend fun record(broadcastId: String, target: BroadcastTarget, outcome: BroadcastOutcome)

    /** Forgets the broadcast [broadcastId], e.g. once it is complete. */
    public suspend fun delete(broadcastId: String)
}

/** Keeps the progress of broadcasts in memory: lost on restart. */
public class InMemoryBroadcastStore : BroadcastStore {
    private val broadcasts = ConcurrentHashMap<String, MutableMap<BroadcastTarget, BroadcastOutcome>>()

    override suspend fun outcomes(broadcastId: String): Map<BroadcastTarget, BroadcastOutcome> =
        broadcasts[broadcastId]?.toMap().orEmpty()

    override suspend fun record(broadcastId: String, target: BroadcastTarget, outcome: BroadcastOutcome) {
        broadcasts.computeIfAbsent(broadcastId) { ConcurrentHashMap() }[target] = outcome
    }

    override suspend fun delete(broadcastId: String) {
        broadcasts.remove(broadcastId)
    }
}

/**
 * Base class for a [BroadcastStore] with blocking I/O, e.g. JDBC, for Java. The methods run on
 * `Dispatchers.IO`.
 */
public abstract class BlockingBroadcastStore : BroadcastStore {
    /** The outcomes recorded for the broadcast [broadcastId]. */
    protected abstract fun load(broadcastId: String): Map<BroadcastTarget, BroadcastOutcome>

    /** Records the [outcome] for [target], replacing an earlier one. */
    protected abstract fun save(broadcastId: String, target: BroadcastTarget, outcome: BroadcastOutcome)

    /** Forgets the broadcast [broadcastId]. */
    protected abstract fun remove(broadcastId: String)

    final override suspend fun outcomes(broadcastId: String): Map<BroadcastTarget, BroadcastOutcome> =
        withContext(Dispatchers.IO) { load(broadcastId) }

    final override suspend fun record(broadcastId: String, target: BroadcastTarget, outcome: BroadcastOutcome): Unit =
        withContext(Dispatchers.IO) { save(broadcastId, target, outcome) }

    final override suspend fun delete(broadcastId: String): Unit = withContext(Dispatchers.IO) { remove(broadcastId) }
}

/**
 * The result of one run of [Broadcaster.send].
 *
 * @property broadcastId the id of the broadcast.
 * @property total number of distinct targets.
 * @property skipped targets handled by earlier runs (sent or unavailable), not sent to again.
 * @property sent targets the message was sent to in this run.
 * @property unavailable targets that cannot get messages ([BroadcastOutcome.UNAVAILABLE]), found in this run.
 * @property failed targets sending to failed in this run, with the last error ([BroadcastOutcome.FAILED]).
 * @property duration how long the run took.
 */
public data class BroadcastReport(
    val broadcastId: String,
    val total: Int,
    val skipped: Int,
    val sent: Int,
    val unavailable: List<BroadcastTarget>,
    val failed: Map<BroadcastTarget, Throwable>,
    val duration: Duration,
) {
    /** `true` if every target got the message or is unavailable, in this run or an earlier one. */
    val isComplete: Boolean get() = failed.isEmpty()
}

/**
 * Sends a message to many users or chats within the rate limits of Max, and keeps the progress so a
 * stopped broadcast continues where it stopped.
 *
 * ```kotlin
 * val broadcaster = Broadcaster(bot.client, JdbcBroadcastStore(dataSource).createTable())
 * val report = broadcaster.send("sale-2026-11", BroadcastTarget.users(ids), message { text("Sale!") })
 * println("Sent ${report.sent}, unavailable ${report.unavailable.size}, failed ${report.failed.size}")
 * ```
 *
 * - Messages go at up to [requestsPerSecond], [parallelism] at a time. Max allows 30 requests per
 *   second for the whole bot, so the default of 25 leaves room for the handlers.
 * - When Max answers 429, all sending pauses for its `Retry-After` (1 second without it) and the
 *   message is sent again, up to [maxAttempts] times in total.
 * - A target that cannot get messages (403: the user stopped the bot or the bot left the chat; 404)
 *   is recorded as [BroadcastOutcome.UNAVAILABLE] and never tried again. Other errors are recorded as
 *   [BroadcastOutcome.FAILED].
 * - The outcome of every target goes to [store] right after it is known. Running [send] again with
 *   the same id skips the targets that were sent to or are unavailable and tries the failed ones
 *   again. A crash between sending and recording means that target gets the message again.
 *
 * @property client the client that sends the messages.
 * @property store where the progress is kept.
 * @property requestsPerSecond the highest rate of messages.
 * @property parallelism how many messages may be on their way at once.
 * @property maxAttempts how many times a message is sent when Max answers 429, the first time included.
 */
public class Broadcaster @JvmOverloads constructor(
    public val client: MaxClient,
    public val store: BroadcastStore = InMemoryBroadcastStore(),
    public val requestsPerSecond: Double = 25.0,
    public val parallelism: Int = 8,
    public val maxAttempts: Int = 5,
) {
    init {
        require(requestsPerSecond > 0) { "requestsPerSecond must be > 0" }
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /**
     * Sends [message] to every target of [targets] that did not get it in an earlier run of the
     * broadcast [id], and returns when all are handled. Cancel the coroutine to stop; the progress
     * is kept.
     *
     * @param id identifies the broadcast in [store]; use a new id for every new broadcast.
     * @throws Exception an error of [store]; the progress recorded before it is kept.
     */
    public suspend fun send(id: String, targets: Collection<BroadcastTarget>, message: BroadcastMessage): BroadcastReport {
        val started = System.nanoTime()
        val distinct = targets.distinct()
        val earlier = store.outcomes(id)
        val pending = distinct.filter { earlier[it] == null || earlier[it] == BroadcastOutcome.FAILED }
        val run = Run(id, message)
        coroutineScope {
            val slots = Semaphore(parallelism)
            for (target in pending) {
                slots.acquire()
                launch {
                    try {
                        run.deliver(target)
                    } finally {
                        slots.release()
                    }
                }
            }
        }
        return BroadcastReport(
            id, distinct.size, distinct.size - pending.size, run.sent.get(),
            pending.filter { it in run.unavailable }, run.failed.toMap(), Duration.ofNanos(System.nanoTime() - started),
        )
    }

    /** Sends the same [body] to every target; see [send]. */
    public suspend fun send(id: String, targets: Collection<BroadcastTarget>, body: NewMessageBody): BroadcastReport =
        send(id, targets) { body }

    /** Blocking version of [send] for Java. */
    public fun sendBlocking(id: String, targets: Collection<BroadcastTarget>, message: BroadcastMessage): BroadcastReport =
        runBlocking { send(id, targets, message) }

    /** Blocking version of [send] for Java. */
    public fun sendBlocking(id: String, targets: Collection<BroadcastTarget>, body: NewMessageBody): BroadcastReport =
        runBlocking { send(id, targets, body) }

    /** One run of a broadcast: its limits and what happened so far. */
    private inner class Run(val id: String, val message: BroadcastMessage) {
        private val bucket = TokenBucket(requestsPerSecond, 1, System::nanoTime)

        /** Until when, in `System.nanoTime()`, all sending waits after a 429. */
        @Volatile
        private var pausedUntil = System.nanoTime()

        val sent = AtomicInteger()
        val unavailable: MutableSet<BroadcastTarget> = ConcurrentHashMap.newKeySet()
        val failed = ConcurrentHashMap<BroadcastTarget, Throwable>()

        suspend fun deliver(target: BroadcastTarget) {
            var attempt = 1
            while (true) {
                waitForPause()
                bucket.acquire()
                val outcome = try {
                    val body = message.bodyFor(target)
                    if (target.kind == BroadcastTarget.Kind.USER) client.sendMessageToUser(target.id, body) else client.sendMessageToChat(target.id, body)
                    BroadcastOutcome.SENT
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TooManyRequestsException) {
                    if (attempt++ < maxAttempts) {
                        pause(e.retryAfter ?: DEFAULT_PAUSE)
                        continue
                    }
                    failed[target] = e
                    BroadcastOutcome.FAILED
                } catch (e: ForbiddenException) {
                    BroadcastOutcome.UNAVAILABLE
                } catch (e: NotFoundException) {
                    BroadcastOutcome.UNAVAILABLE
                } catch (e: Exception) {
                    failed[target] = e
                    BroadcastOutcome.FAILED
                }
                when (outcome) {
                    BroadcastOutcome.SENT -> sent.incrementAndGet()
                    BroadcastOutcome.UNAVAILABLE -> unavailable += target
                    BroadcastOutcome.FAILED -> Unit
                }
                store.record(id, target, outcome)
                return
            }
        }

        private suspend fun waitForPause() {
            val waitNanos = pausedUntil - System.nanoTime()
            if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
        }

        @Synchronized
        private fun pause(duration: Duration) {
            pausedUntil = maxOf(pausedUntil, System.nanoTime() + duration.toNanos())
        }
    }

    private companion object {
        val DEFAULT_PAUSE: Duration = Duration.ofSeconds(1)
    }
}
