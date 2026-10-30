package ru.securitytrip.maxapi.redis

import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import ru.securitytrip.maxapi.bot.SuspendUpdateDeduplicator
import ru.securitytrip.maxapi.bot.UpdateDeduplicator
import ru.securitytrip.maxapi.model.update.Update
import java.time.Duration

/**
 * Remembers the keys ([UpdateDeduplicator.keyOf]) of handled updates in Redis for [ttl] with
 * `SET NX`, so a repeated delivery is dropped even when another instance of the bot received the
 * first one. Max retries webhook deliveries for up to 8 hours, hence the default of 9 hours.
 *
 * The bot calls [markSeen], which uses the asynchronous API of Lettuce and does not block the thread
 * that dispatches the update; [isNew] is the blocking equivalent for other callers.
 */
public class RedisUpdateDeduplicator @JvmOverloads constructor(
    private val connection: StatefulRedisConnection<String, String>,
    /** Prefix of the keys; give every bot that shares the Redis database its own. */
    public val prefix: String = DEFAULT_PREFIX,
    /** How long a key is remembered. */
    public val ttl: Duration = Duration.ofHours(9),
) : UpdateDeduplicator, SuspendUpdateDeduplicator {
    init {
        require(ttl > Duration.ZERO) { "ttl must be positive" }
    }

    override fun isNew(update: Update): Boolean = connection.sync().set(keyOf(update), "1", args()) == "OK"

    override suspend fun markSeen(update: Update): Boolean = connection.async().set(keyOf(update), "1", args()).await() == "OK"

    private fun keyOf(update: Update): String = prefix + UpdateDeduplicator.keyOf(update)

    private fun args(): SetArgs = SetArgs.Builder.nx().px(ttl.toMillis())

    public companion object {
        /** Default key prefix. */
        public const val DEFAULT_PREFIX: String = "max:seen:"
    }
}
