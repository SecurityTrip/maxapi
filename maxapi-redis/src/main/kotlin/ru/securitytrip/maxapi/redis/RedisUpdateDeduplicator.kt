package ru.securitytrip.maxapi.redis

import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import ru.securitytrip.maxapi.bot.UpdateDeduplicator
import ru.securitytrip.maxapi.model.update.Update
import java.time.Duration

/**
 * Remembers the keys ([UpdateDeduplicator.keyOf]) of handled updates in Redis for [ttl] with
 * `SET NX`, so a repeated delivery is dropped even when another instance of the bot received the
 * first one. Max retries webhook deliveries for up to 8 hours, hence the default of 9 hours.
 */
public class RedisUpdateDeduplicator @JvmOverloads constructor(
    private val connection: StatefulRedisConnection<String, String>,
    /** Prefix of the keys; give every bot that shares the Redis database its own. */
    public val prefix: String = DEFAULT_PREFIX,
    /** How long a key is remembered. */
    public val ttl: Duration = Duration.ofHours(9),
) : UpdateDeduplicator {
    init {
        require(ttl > Duration.ZERO) { "ttl must be positive" }
    }

    override fun isNew(update: Update): Boolean =
        connection.sync().set(prefix + UpdateDeduplicator.keyOf(update), "1", SetArgs.Builder.nx().px(ttl.toMillis())) == "OK"

    public companion object {
        /** Default key prefix. */
        public const val DEFAULT_PREFIX: String = "max:seen:"
    }
}
