package ru.securitytrip.maxapi.spring

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.securitytrip.maxapi.bot.MarkerStore
import ru.securitytrip.maxapi.bot.StateStorage
import ru.securitytrip.maxapi.bot.UpdateDeduplicator
import ru.securitytrip.maxapi.jdbc.JdbcMarkerStore
import ru.securitytrip.maxapi.jdbc.JdbcStateStorage
import ru.securitytrip.maxapi.jdbc.JdbcUpdateDeduplicator
import ru.securitytrip.maxapi.redis.RedisMarkerStore
import ru.securitytrip.maxapi.redis.RedisStateStorage
import ru.securitytrip.maxapi.redis.RedisUpdateDeduplicator
import javax.sql.DataSource

/**
 * Creates the storages that `max.bot.storage` selects: `jdbc` keeps them in the application's
 * `DataSource` (with `maxapi-jdbc`), `redis` in Redis at `max.bot.redis.url` (with `maxapi-redis`).
 * Your own [StateStorage], [MarkerStore] or [UpdateDeduplicator] bean replaces the matching one.
 */
@AutoConfiguration(before = [MaxBotAutoConfiguration::class])
public class MaxStorageAutoConfiguration {
    /** Storages in tables of the application's `DataSource`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["ru.securitytrip.maxapi.jdbc.JdbcStateStorage"])
    @ConditionalOnProperty(prefix = "max.bot", name = ["storage"], havingValue = "jdbc")
    public class Jdbc {
        @Bean
        @ConditionalOnMissingBean(StateStorage::class)
        public fun maxJdbcStateStorage(dataSource: DataSource, properties: MaxBotProperties): JdbcStateStorage =
            JdbcStateStorage(dataSource, properties.jdbc.tablePrefix + "dialog_state")
                .also { if (properties.jdbc.createTables) it.createTable() }

        @Bean
        @ConditionalOnMissingBean(MarkerStore::class)
        public fun maxJdbcMarkerStore(dataSource: DataSource, properties: MaxBotProperties): JdbcMarkerStore =
            JdbcMarkerStore(dataSource, properties.jdbc.tablePrefix + "bot_marker")
                .also { if (properties.jdbc.createTables) it.createTable() }

        @Bean
        @ConditionalOnMissingBean(UpdateDeduplicator::class)
        public fun maxJdbcUpdateDeduplicator(dataSource: DataSource, properties: MaxBotProperties): JdbcUpdateDeduplicator =
            JdbcUpdateDeduplicator(dataSource, properties.jdbc.tablePrefix + "seen_update")
                .also { if (properties.jdbc.createTables) it.createTable() }
    }

    /** Storages in Redis at `max.bot.redis.url`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["ru.securitytrip.maxapi.redis.RedisStateStorage"])
    @ConditionalOnProperty(prefix = "max.bot", name = ["storage"], havingValue = "redis")
    public class Redis {
        /** The Redis client, shut down with the application context. */
        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean
        public fun maxRedisClient(properties: MaxBotProperties): RedisClient = RedisClient.create(properties.redis.url)

        @Bean
        @ConditionalOnMissingBean
        public fun maxRedisConnection(client: RedisClient): StatefulRedisConnection<String, String> = client.connect()

        @Bean
        @ConditionalOnMissingBean(StateStorage::class)
        public fun maxRedisStateStorage(
            connection: StatefulRedisConnection<String, String>,
            properties: MaxBotProperties,
        ): RedisStateStorage = RedisStateStorage(connection, properties.redis.keyPrefix + "state:", properties.redis.stateTtl)

        @Bean
        @ConditionalOnMissingBean(MarkerStore::class)
        public fun maxRedisMarkerStore(
            connection: StatefulRedisConnection<String, String>,
            properties: MaxBotProperties,
        ): RedisMarkerStore = RedisMarkerStore(connection, properties.redis.keyPrefix + "marker")

        @Bean
        @ConditionalOnMissingBean(UpdateDeduplicator::class)
        public fun maxRedisUpdateDeduplicator(
            connection: StatefulRedisConnection<String, String>,
            properties: MaxBotProperties,
        ): RedisUpdateDeduplicator = RedisUpdateDeduplicator(connection, properties.redis.keyPrefix + "seen:")
    }
}
