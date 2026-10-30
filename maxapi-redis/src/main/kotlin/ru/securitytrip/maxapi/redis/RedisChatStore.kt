package ru.securitytrip.maxapi.redis

import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import ru.securitytrip.maxapi.bot.ChatStore
import ru.securitytrip.maxapi.bot.KnownChat
import ru.securitytrip.maxapi.model.chat.ChatType

/**
 * Keeps the chats of the bot ([ChatStore]) in the Redis hash [key], one field per chat with a JSON
 * value, so they survive restarts and are shared by all instances of the bot.
 *
 * A save runs a Lua script that compares the times of the events, so instances that save the same
 * chat at once never replace a newer record with an older one.
 *
 * ```kotlin
 * val redis = RedisClient.create("redis://localhost:6379").connect()
 * val bot = MaxBot(token, BotConfig(chatStore = RedisChatStore(redis)))
 * ```
 */
public class RedisChatStore @JvmOverloads constructor(
    private val connection: StatefulRedisConnection<String, String>,
    /** Key of the hash; give every bot that shares the Redis database its own. */
    public val key: String = DEFAULT_KEY,
) : ChatStore {
    override suspend fun get(chatId: Long): KnownChat? =
        connection.async().hget(key, chatId.toString()).await()?.let { decode(chatId, it) }

    override suspend fun save(chat: KnownChat): Boolean {
        val saved = connection.async()
            .eval<Long>(SAVE, ScriptOutputType.INTEGER, arrayOf(key), chat.chatId.toString(), encode(chat), chat.updatedAt.toString())
            .await()
        return saved == 1L
    }

    override suspend fun list(): List<KnownChat> =
        connection.async().hgetall(key).await().map { (field, value) -> decode(field.toLong(), value) }.filter { it.active }

    public companion object {
        /** Default key of the hash. */
        public const val DEFAULT_KEY: String = "max:chats"

        private val mapper = ObjectMapper()

        /** Sets the field unless the stored value has a later `updatedAt`; returns 1 if it was set. */
        private val SAVE = """
            local old = redis.call('HGET', KEYS[1], ARGV[1])
            if old and tonumber(cjson.decode(old)['updatedAt']) > tonumber(ARGV[3]) then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
        """.trimIndent()

        private fun encode(chat: KnownChat): String = mapper.writeValueAsString(
            mapOf("type" to chat.type.value, "title" to chat.title, "userId" to chat.userId, "active" to chat.active, "updatedAt" to chat.updatedAt),
        )

        private fun decode(chatId: Long, json: String): KnownChat {
            val tree = mapper.readTree(json)
            val type = tree.path("type").asText()
            return KnownChat(
                chatId = chatId,
                type = ChatType.entries.firstOrNull { it.value == type } ?: ChatType.UNKNOWN,
                title = tree.path("title").textValue(),
                userId = tree.path("userId").takeIf { it.isNumber }?.asLong(),
                active = tree.path("active").asBoolean(),
                updatedAt = tree.path("updatedAt").asLong(),
            )
        }
    }
}
