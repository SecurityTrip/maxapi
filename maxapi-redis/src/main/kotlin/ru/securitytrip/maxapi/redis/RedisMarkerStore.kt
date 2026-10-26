package ru.securitytrip.maxapi.redis

import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import ru.securitytrip.maxapi.bot.MarkerStore

/** Keeps the long polling marker in Redis under [key], so polling continues where it stopped after a restart. */
public class RedisMarkerStore @JvmOverloads constructor(
    private val connection: StatefulRedisConnection<String, String>,
    /** Key of the marker; give every bot that shares the Redis database its own. */
    public val key: String = DEFAULT_KEY,
) : MarkerStore {
    override suspend fun load(): Long? = connection.async().get(key).await()?.toLongOrNull()

    override suspend fun save(marker: Long) {
        connection.async().set(key, marker.toString()).await()
    }

    public companion object {
        /** Default key. */
        public const val DEFAULT_KEY: String = "max:marker"
    }
}
