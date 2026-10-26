package ru.securitytrip.maxapi.redis

import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import ru.securitytrip.maxapi.bot.DialogState
import ru.securitytrip.maxapi.bot.StateKey
import ru.securitytrip.maxapi.bot.StateStorage
import java.time.Duration

/**
 * Keeps dialog states in Redis under `[prefix]<chat>:<user>`, so they survive restarts and are shared
 * by all instances of the bot. Each state is a JSON string; with a [ttl], Redis deletes states not
 * changed for that long, e.g. dialogs users abandoned.
 *
 * ```kotlin
 * val redis = RedisClient.create("redis://localhost:6379").connect()
 * val bot = MaxBot(token, BotConfig(stateStorage = RedisStateStorage(redis)))
 * ```
 */
public class RedisStateStorage @JvmOverloads constructor(
    private val connection: StatefulRedisConnection<String, String>,
    /** Prefix of the keys; give every bot that shares the Redis database its own. */
    public val prefix: String = DEFAULT_PREFIX,
    /** How long a state is kept after its last change; `null` keeps it until it is cleared. */
    public val ttl: Duration? = null,
) : StateStorage {
    init {
        require(ttl == null || ttl > Duration.ZERO) { "ttl must be positive" }
    }

    override suspend fun get(key: StateKey): DialogState? =
        connection.async().get(keyOf(key)).await()?.let(::decode)

    override suspend fun set(key: StateKey, state: DialogState?) {
        val commands = connection.async()
        when {
            state == null -> commands.del(keyOf(key)).await()
            ttl == null -> commands.set(keyOf(key), encode(state)).await()
            else -> commands.set(keyOf(key), encode(state), SetArgs.Builder.px(ttl.toMillis())).await()
        }
    }

    private fun keyOf(key: StateKey): String = "$prefix${key.chatId ?: ""}:${key.userId ?: ""}"

    public companion object {
        /** Default key prefix. */
        public const val DEFAULT_PREFIX: String = "max:state:"

        private val mapper = ObjectMapper()

        private fun encode(state: DialogState): String =
            mapper.writeValueAsString(mapOf("name" to state.name, "data" to state.data))

        private fun decode(json: String): DialogState {
            val tree = mapper.readTree(json)
            val data = tree.path("data").properties().associate { (name, value) -> name to value.asText() }
            return DialogState(tree.path("name").textValue(), data)
        }
    }
}
