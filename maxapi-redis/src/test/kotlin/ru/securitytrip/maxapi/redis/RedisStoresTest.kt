package ru.securitytrip.maxapi.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.GenericContainer
import ru.securitytrip.maxapi.bot.DialogState
import ru.securitytrip.maxapi.bot.StateKey
import ru.securitytrip.maxapi.bot.StateStorage
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.model.update.Update
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs against Redis in Docker. */
class RedisStoresTest {
    private val connection: StatefulRedisConnection<String, String> = client.connect()
    private val prefix = "test${counter.incrementAndGet()}:"

    @AfterTest
    fun close() {
        connection.close()
    }

    @Test
    fun `states are stored, replaced and removed`(): Unit = runBlocking {
        val storage: StateStorage = RedisStateStorage(connection, prefix + "state:")
        val key = StateKey(1, 2)
        assertNull(storage.get(key))

        storage.set(key, DialogState("name", mapOf("city" to "Казань", "quote" to "a \"b\"")))
        assertEquals(DialogState("name", mapOf("city" to "Казань", "quote" to "a \"b\"")), storage.get(key))
        assertEquals(-1, connection.sync().ttl(prefix + "state:1:2"))

        storage.set(key, DialogState(null))
        assertEquals(DialogState(null), storage.get(key))

        storage.set(StateKey(1, null), DialogState("chat"))
        storage.set(StateKey(null, 2), DialogState("user"))
        assertEquals("chat", storage.get(StateKey(1, null))?.name)
        assertEquals("user", storage.get(StateKey(null, 2))?.name)

        storage.set(key, null)
        assertNull(storage.get(key))
        assertEquals(RedisStateStorage.DEFAULT_PREFIX, RedisStateStorage(connection).prefix)
    }

    @Test
    fun `states expire after the ttl`(): Unit = runBlocking {
        val storage = RedisStateStorage(connection, prefix, Duration.ofHours(1))
        storage.set(StateKey(1, 1), DialogState("step"))
        val ttl = connection.sync().pttl("${prefix}1:1")
        assertTrue(ttl in 1..3_600_000, "ttl was $ttl")
        assertEquals(Duration.ofHours(1), storage.ttl)
        assertFailsWith<IllegalArgumentException> { RedisStateStorage(connection, ttl = Duration.ZERO) }
    }

    @Test
    fun `markers are kept per key`(): Unit = runBlocking {
        val first = RedisMarkerStore(connection, prefix + "marker")
        val second = RedisMarkerStore(connection, prefix + "other")
        assertNull(first.load())

        first.save(10)
        first.save(42)
        second.save(7)
        assertEquals(42, first.load())
        assertEquals(7, second.load())
        assertEquals(RedisMarkerStore.DEFAULT_KEY, RedisMarkerStore(connection).key)
    }

    @Test
    fun `repeated updates are dropped, also by another instance`() {
        val first = RedisUpdateDeduplicator(connection, prefix)
        val second = RedisUpdateDeduplicator(client.connect(), prefix, Duration.ofMinutes(5))

        assertTrue(first.isNew(message("mid.1")))
        assertFalse(first.isNew(message("mid.1")))
        assertFalse(second.isNew(message("mid.1")))
        assertTrue(second.isNew(message("mid.2")))

        val ttl = connection.sync().pttl(prefix + ru.securitytrip.maxapi.bot.UpdateDeduplicator.keyOf(message("mid.2")))
        assertTrue(ttl in 1..300_000, "ttl was $ttl")
        assertEquals(Duration.ofHours(9), first.ttl)
        assertEquals(RedisUpdateDeduplicator.DEFAULT_PREFIX, RedisUpdateDeduplicator(connection).prefix)
        assertFailsWith<IllegalArgumentException> { RedisUpdateDeduplicator(connection, ttl = Duration.ofMillis(-1)) }
    }

    private fun message(mid: String): Update = MaxJson.parseUpdate(
        """{"update_type":"message_created","timestamp":1,"message":{"recipient":{"chat_id":1,"chat_type":"dialog"},
           "timestamp":1,"body":{"mid":"$mid","seq":1,"text":"hi"}}}""",
    )

    private companion object {
        val counter = AtomicInteger()
        val redis = GenericContainer("redis:7.4-alpine").withExposedPorts(6379).apply { start() }
        val client: RedisClient = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(6379)}")
    }
}
