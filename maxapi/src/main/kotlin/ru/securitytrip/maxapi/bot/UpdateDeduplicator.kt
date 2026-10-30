package ru.securitytrip.maxapi.bot

import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.update.Update

/**
 * Drops updates the bot has already seen, set with [BotConfig.deduplicator]. Max redelivers a webhook
 * update until it gets `200 OK`, for up to 8 hours, and polling with a [MarkerStore] may repeat updates
 * after a restart, so the same update can arrive more than once.
 *
 * [isNew] blocks the thread that dispatches the update. A store with a non-blocking client should
 * implement [SuspendUpdateDeduplicator] instead, or as well: the bot calls [SuspendUpdateDeduplicator.markSeen]
 * of a deduplicator that implements both.
 */
public fun interface UpdateDeduplicator {
    /** `true` if [update] is seen for the first time and should be handled; `false` for a duplicate. */
    public fun isNew(update: Update): Boolean

    public companion object {
        /**
         * A key that is the same for deliveries of the same event: the message id for new messages,
         * the callback id for button presses, and type, time, chat and user for other updates.
         */
        @JvmStatic
        public fun keyOf(update: Update): String = when (update) {
            is MessageCreatedUpdate -> "message_created:${update.message.body?.mid ?: update.timestamp}"
            is MessageCallbackUpdate -> "message_callback:${update.callback.callbackId}"
            else -> "${update.updateType}:${update.timestamp}:${update.chatId}:${update.user?.userId}"
        }
    }
}

/**
 * Non-blocking version of [UpdateDeduplicator] for stores with an asynchronous client, set with
 * [BotConfig.suspendDeduplicator] (or with [BotConfig.deduplicator] by a class that implements both
 * interfaces, like `RedisUpdateDeduplicator`).
 *
 * ```kotlin
 * val deduplicator = SuspendUpdateDeduplicator { update -> seenKeys.addIfAbsent(UpdateDeduplicator.keyOf(update)) }
 * val bot = MaxBot(token, BotConfig(suspendDeduplicator = deduplicator))
 * ```
 */
public fun interface SuspendUpdateDeduplicator {
    /**
     * Records [update] as seen. `true` if it is seen for the first time and should be handled; `false`
     * for a duplicate. Like `Set.add`, only the first call for an update returns `true`.
     */
    public suspend fun markSeen(update: Update): Boolean
}

/**
 * Remembers the keys ([UpdateDeduplicator.keyOf]) of the last [capacity] updates in memory.
 * Use a shared store (e.g. Redis `SET NX` with a TTL) when several instances receive the updates.
 */
public class InMemoryUpdateDeduplicator @JvmOverloads constructor(private val capacity: Int = 10_000) : UpdateDeduplicator {
    init {
        require(capacity >= 1) { "capacity must be >= 1" }
    }

    private val seen = object : LinkedHashMap<String, Unit>(16, 0.75f) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > capacity
    }

    override fun isNew(update: Update): Boolean = synchronized(seen) {
        seen.put(UpdateDeduplicator.keyOf(update), Unit) == null
    }
}
