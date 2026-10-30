package ru.securitytrip.maxapi.spring

import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.BeanFactory
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import ru.securitytrip.maxapi.bot.MaxBot
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Health of the bot, the `bot` part of the health contributor `max`:
 *
 * - polling mode: `UP` while long polling runs; `DOWN` once it ended, with the error that ended it;
 * - webhook mode: `DOWN` without the webhook endpoint (not a servlet or reactive web application) or
 *   when the subscription to `max.bot.webhook.url` is missing (the starter renews it on its next check);
 * - in every mode: `DOWN` when the API cannot be reached or rejects the token.
 *
 * The request to the API (`getSubscriptions` in webhook mode, `getMyInfo` otherwise) is made at most
 * once per `max.bot.health.cache-ttl`, so frequent health checks do not use up the request limit.
 */
public class MaxBotHealthIndicator internal constructor(
    private val bot: MaxBot,
    private val properties: MaxBotProperties,
    private val webhookEndpoint: Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
) : AbstractHealthIndicator("Max bot health check failed") {
    private val lock = Any()

    @Volatile
    private var cached: Cached? = null

    /** A result of the API request and when it was received. */
    private class Cached(val at: Long, val result: Result<Any>)

    override fun doHealthCheck(builder: Health.Builder) {
        builder.up().withDetail("mode", properties.mode.name.lowercase())
        when (properties.mode) {
            MaxBotProperties.Mode.POLLING -> {
                checkPolling(builder)
                checkApi(builder) { bot.client.getMyInfo() }.onSuccess { builder.withDetail("username", it.username.orEmpty()) }
            }
            MaxBotProperties.Mode.WEBHOOK -> checkWebhook(builder)
            MaxBotProperties.Mode.NONE ->
                checkApi(builder) { bot.client.getMyInfo() }.onSuccess { builder.withDetail("username", it.username.orEmpty()) }
        }
    }

    private fun checkPolling(builder: Health.Builder) {
        val failure = bot.pollingFailure
        val state = when {
            bot.isPolling -> "running"
            failure != null -> "failed"
            else -> "stopped"
        }
        builder.withDetail("polling", state)
        if (failure != null) builder.withDetail("pollingError", describe(failure))
        if (state != "running") builder.down()
    }

    private fun checkWebhook(builder: Health.Builder) {
        val webhook = properties.webhook
        val details = linkedMapOf<String, Any>("path" to webhook.path, "endpoint" to webhookEndpoint)
        if (!webhookEndpoint) builder.down()
        val url = webhook.url
        checkApi(builder) { bot.client.getSubscriptions().map { it.url } }.onSuccess { urls ->
            details["subscriptions"] = urls
            if (url != null) {
                details["url"] = url
                details["subscribed"] = url in urls
                if (url !in urls) builder.down()
            }
        }
        builder.withDetail("webhook", details)
    }

    /** Calls the API at most once per `cache-ttl`; a failure makes the health `DOWN`. */
    private fun <T : Any> checkApi(builder: Health.Builder, call: suspend () -> T): Result<T> {
        @Suppress("UNCHECKED_CAST")
        val result = cachedCall(call) as Result<T>
        result.onFailure {
            builder.down().withDetail("api", "DOWN").withDetail("apiError", describe(it))
        }.onSuccess {
            builder.withDetail("api", "UP")
        }
        return result
    }

    private fun cachedCall(call: suspend () -> Any): Result<Any> = synchronized(lock) {
        val ttl = properties.health.cacheTtl.toNanos()
        cached?.takeIf { ttl > 0 && nanoTime() - it.at < ttl }?.let { return it.result }
        val result = try {
            Result.success(runBlocking { withTimeout(properties.health.timeout.toMillis()) { call() } })
        } catch (e: Exception) {
            Result.failure(e)
        }
        cached = Cached(nanoTime(), result)
        result
    }
}

/** Reachability of the database of `max.bot.storage=jdbc`, the `storage` part of the health contributor `max`. */
internal class JdbcStorageHealthIndicator(private val dataSource: DataSource, private val timeout: Duration) :
    AbstractHealthIndicator("Max bot storage health check failed") {
    override fun doHealthCheck(builder: Health.Builder) {
        dataSource.connection.use { connection ->
            builder.withDetail("storage", "jdbc").withDetail("database", connection.metaData.databaseProductName)
            if (connection.isValid(timeout.toSeconds().toInt().coerceAtLeast(1))) builder.up() else builder.down()
        }
    }
}

/** Reachability of Redis of `max.bot.storage=redis`, the `storage` part of the health contributor `max`. */
internal class RedisStorageHealthIndicator(
    private val connection: StatefulRedisConnection<String, String>,
    private val timeout: Duration,
) : AbstractHealthIndicator("Max bot storage health check failed") {
    override fun doHealthCheck(builder: Health.Builder) {
        val pong = connection.async().ping().get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        builder.up().withDetail("storage", "redis").withDetail("ping", pong)
    }

    companion object {
        /** The indicator of the Redis connection bean; this class alone refers to Lettuce, so it loads only with it. */
        fun of(beans: BeanFactory, timeout: Duration): RedisStorageHealthIndicator? =
            beans.getBeanProvider(StatefulRedisConnection::class.java).ifAvailable?.let {
                @Suppress("UNCHECKED_CAST")
                RedisStorageHealthIndicator(it as StatefulRedisConnection<String, String>, timeout)
            }
    }
}

private fun describe(error: Throwable): String = error.message?.let { "${error.javaClass.simpleName}: $it" } ?: error.javaClass.simpleName
