package ru.securitytrip.maxapi.redis

import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import ru.securitytrip.maxapi.bot.BroadcastOutcome
import ru.securitytrip.maxapi.bot.BroadcastStore
import ru.securitytrip.maxapi.bot.BroadcastTarget
import java.time.Duration

/**
 * Keeps the progress of broadcasts ([BroadcastStore]) in Redis, one hash per broadcast under
 * `[prefix]<broadcast id>` with a field per target, so a broadcast continues after a restart. With a
 * [ttl], Redis deletes a broadcast that was not changed for that long.
 */
public class RedisBroadcastStore @JvmOverloads constructor(
    private val connection: StatefulRedisConnection<String, String>,
    /** Prefix of the keys; give every bot that shares the Redis database its own. */
    public val prefix: String = DEFAULT_PREFIX,
    /** How long a broadcast is kept after its last change; `null` keeps it until it is deleted. */
    public val ttl: Duration? = null,
) : BroadcastStore {
    init {
        require(ttl == null || ttl > Duration.ZERO) { "ttl must be positive" }
    }

    override suspend fun outcomes(broadcastId: String): Map<BroadcastTarget, BroadcastOutcome> =
        connection.async().hgetall(prefix + broadcastId).await()
            .entries.associate { (target, outcome) -> BroadcastTarget.parse(target) to BroadcastOutcome.valueOf(outcome) }

    override suspend fun record(broadcastId: String, target: BroadcastTarget, outcome: BroadcastOutcome) {
        val commands = connection.async()
        commands.hset(prefix + broadcastId, target.key, outcome.name).await()
        ttl?.let { commands.pexpire(prefix + broadcastId, it.toMillis()).await() }
    }

    override suspend fun delete(broadcastId: String) {
        connection.async().del(prefix + broadcastId).await()
    }

    public companion object {
        /** Default key prefix. */
        public const val DEFAULT_PREFIX: String = "max:broadcast:"
    }
}
