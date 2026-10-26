package ru.securitytrip.maxapi.spring

import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.testcontainers.containers.GenericContainer
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.DialogState
import ru.securitytrip.maxapi.bot.InMemoryStateStorage
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.StateKey
import ru.securitytrip.maxapi.bot.StateStorage
import ru.securitytrip.maxapi.jdbc.JdbcMarkerStore
import ru.securitytrip.maxapi.jdbc.JdbcStateStorage
import ru.securitytrip.maxapi.jdbc.JdbcUpdateDeduplicator
import ru.securitytrip.maxapi.redis.RedisMarkerStore
import ru.securitytrip.maxapi.redis.RedisStateStorage
import ru.securitytrip.maxapi.redis.RedisUpdateDeduplicator
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaxStorageAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MaxStorageAutoConfiguration::class.java, MaxBotAutoConfiguration::class.java))
        .withPropertyValues("max.bot.token=t", "max.bot.mode=none")

    private fun dataSource(): DataSource =
        JdbcDataSource().apply { setURL("jdbc:h2:mem:starter${databases.incrementAndGet()};DB_CLOSE_DELAY=-1") }

    private fun tableExists(dataSource: DataSource, table: String): Boolean = try {
        dataSource.connection.use { c -> c.createStatement().use { it.executeQuery("SELECT 1 FROM $table").close() } }
        true
    } catch (e: SQLException) {
        false
    }

    @Test
    fun `states are kept in memory by default`() {
        runner.run { context ->
            val config = context.getBean(MaxBot::class.java).config
            assertIs<InMemoryStateStorage>(config.stateStorage)
            assertNull(config.markerStore)
            assertNull(config.deduplicator)
            assertSame(config, context.getBean(BotConfig::class.java))
        }
    }

    @Test
    fun `jdbc storage uses the DataSource and creates the tables`() {
        val dataSource = dataSource()
        runner.withPropertyValues("max.bot.storage=jdbc")
            .withBean(DataSource::class.java, { dataSource })
            .run { context ->
                val config = context.getBean(MaxBot::class.java).config
                assertEquals("max_dialog_state", assertIs<JdbcStateStorage>(config.stateStorage).table)
                assertEquals("max_bot_marker", assertIs<JdbcMarkerStore>(config.markerStore).table)
                assertEquals("max_seen_update", assertIs<JdbcUpdateDeduplicator>(config.deduplicator).table)
                runBlocking { config.stateStorage.set(StateKey(1, 1), DialogState("step")) }
            }
        for (table in listOf("max_dialog_state", "max_bot_marker", "max_seen_update")) assertTrue(tableExists(dataSource, table), table)
    }

    @Test
    fun `jdbc tables can be named and left to migrations`() {
        val dataSource = dataSource()
        runner.withPropertyValues("max.bot.storage=jdbc", "max.bot.jdbc.table-prefix=bot1_", "max.bot.jdbc.create-tables=false")
            .withBean(DataSource::class.java, { dataSource })
            .run { context ->
                val config = context.getBean(MaxBot::class.java).config
                assertEquals("bot1_dialog_state", assertIs<JdbcStateStorage>(config.stateStorage).table)
                assertEquals("bot1_bot_marker", assertIs<JdbcMarkerStore>(config.markerStore).table)
                assertEquals("bot1_seen_update", assertIs<JdbcUpdateDeduplicator>(config.deduplicator).table)
            }
        assertTrue(!tableExists(dataSource, "bot1_dialog_state"))
    }

    @Test
    fun `own storage beans take precedence`() {
        val own = InMemoryStateStorage()
        runner.withPropertyValues("max.bot.storage=jdbc")
            .withBean(DataSource::class.java, { dataSource() })
            .withBean(StateStorage::class.java, { own })
            .run { context ->
                val config = context.getBean(MaxBot::class.java).config
                assertSame(own, config.stateStorage)
                assertIs<JdbcMarkerStore>(config.markerStore)
            }
    }

    @Test
    fun `jdbc storage needs maxapi-jdbc`() {
        runner.withPropertyValues("max.bot.storage=jdbc")
            .withClassLoader(FilteredClassLoader(JdbcStateStorage::class.java))
            .run { context -> assertIs<InMemoryStateStorage>(context.getBean(MaxBot::class.java).config.stateStorage) }
    }

    @Test
    fun `redis storage connects to the configured url`() {
        runner.withPropertyValues("max.bot.storage=redis", "max.bot.redis.url=$redisUrl", "max.bot.redis.state-ttl=1h")
            .run { context ->
                val config = context.getBean(MaxBot::class.java).config
                val storage = assertIs<RedisStateStorage>(config.stateStorage)
                assertEquals("max:state:", storage.prefix)
                assertEquals(Duration.ofHours(1), storage.ttl)
                assertEquals("max:marker", assertIs<RedisMarkerStore>(config.markerStore).key)
                assertEquals("max:seen:", assertIs<RedisUpdateDeduplicator>(config.deduplicator).prefix)
                runBlocking {
                    storage.set(StateKey(1, 1), DialogState("step"))
                    assertEquals("step", storage.get(StateKey(1, 1))?.name)
                }
            }
    }

    @Test
    fun `redis keys can have their own prefix`() {
        runner.withPropertyValues("max.bot.storage=redis", "max.bot.redis.url=$redisUrl", "max.bot.redis.key-prefix=bot1:")
            .run { context ->
                val config = context.getBean(MaxBot::class.java).config
                assertEquals("bot1:state:", assertIs<RedisStateStorage>(config.stateStorage).prefix)
                assertNull((config.stateStorage as RedisStateStorage).ttl)
                assertEquals("bot1:marker", assertIs<RedisMarkerStore>(config.markerStore).key)
                assertEquals("bot1:seen:", assertIs<RedisUpdateDeduplicator>(config.deduplicator).prefix)
                assertIs<RedisClient>(context.getBean(RedisClient::class.java))
            }
    }

    @Test
    fun `redis storage needs maxapi-redis`() {
        runner.withPropertyValues("max.bot.storage=redis")
            .withClassLoader(FilteredClassLoader(RedisStateStorage::class.java))
            .run { context -> assertIs<InMemoryStateStorage>(context.getBean(MaxBot::class.java).config.stateStorage) }
    }

    @Test
    fun `storage properties have defaults`() {
        val properties = MaxBotProperties()
        assertEquals(MaxBotProperties.Storage.MEMORY, properties.storage)
        assertEquals("max_", properties.jdbc.tablePrefix)
        assertTrue(properties.jdbc.createTables)
        assertEquals("redis://localhost:6379", properties.redis.url)
        assertEquals("max:", properties.redis.keyPrefix)
        assertNull(properties.redis.stateTtl)
        assertFailsWith<IllegalStateException> {
            runner.withPropertyValues("max.bot.storage=jdbc").run { context -> context.getBean(MaxBot::class.java) }
        }
    }

    private companion object {
        val databases = AtomicInteger()
        val redis = GenericContainer("redis:7.4-alpine").withExposedPorts(6379).apply { start() }
        val redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
    }
}
