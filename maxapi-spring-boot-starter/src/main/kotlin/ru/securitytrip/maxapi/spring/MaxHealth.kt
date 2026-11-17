package ru.securitytrip.maxapi.spring

import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.BeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.util.ClassUtils
import ru.securitytrip.maxapi.bot.MaxBot
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

// The checks behind the health contributor `max`, without the types of Spring Boot: its health API moved
// to other packages in Spring Boot 4, and the adapters of MaxBoot3Health.kt and of the boot4 source set
// turn these reports into the health of either version.

/** The result of a health check: up or down, with details. */
internal class HealthReport {
    var up: Boolean = true
        private set

    val details: MutableMap<String, Any> = linkedMapOf()

    fun down(): HealthReport = apply { up = false }

    fun detail(name: String, value: Any): HealthReport = apply { details[name] = value }
}

/**
 * Health of the bot, the `bot` part of the health contributor `max`:
 *
 * - polling mode: up while long polling runs; down once it ended, with the error that ended it;
 * - webhook mode: down without the webhook endpoint (not a servlet or reactive web application) or
 *   when the subscription to `max.bot.webhook.url` is missing (the starter renews it on its next check);
 * - in every mode: down when the API cannot be reached or rejects the token.
 *
 * The request to the API (`getSubscriptions` in webhook mode, `getMyInfo` otherwise) is made at most
 * once per `max.bot.health.cache-ttl`, so frequent health checks do not use up the request limit.
 */
internal class BotHealthCheck(
    private val bot: MaxBot,
    private val properties: MaxBotProperties,
    private val webhookEndpoint: Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()

    @Volatile
    private var cached: Cached? = null

    /** A result of the API request and when it was received. */
    private class Cached(val at: Long, val result: Result<Any>)

    fun check(): HealthReport {
        val report = HealthReport().detail("mode", properties.mode.name.lowercase())
        when (properties.mode) {
            MaxBotProperties.Mode.POLLING -> {
                checkPolling(report)
                checkApi(report) { bot.client.getMyInfo() }.onSuccess { report.detail("username", it.username.orEmpty()) }
            }
            MaxBotProperties.Mode.WEBHOOK -> checkWebhook(report)
            MaxBotProperties.Mode.NONE ->
                checkApi(report) { bot.client.getMyInfo() }.onSuccess { report.detail("username", it.username.orEmpty()) }
        }
        return report
    }

    private fun checkPolling(report: HealthReport) {
        val failure = bot.pollingFailure
        val state = when {
            bot.isPolling -> "running"
            failure != null -> "failed"
            else -> "stopped"
        }
        report.detail("polling", state)
        if (failure != null) report.detail("pollingError", describe(failure))
        if (state != "running") report.down()
    }

    private fun checkWebhook(report: HealthReport) {
        val webhook = properties.webhook
        val details = linkedMapOf<String, Any>("path" to webhook.path, "endpoint" to webhookEndpoint)
        if (!webhookEndpoint) report.down()
        val url = webhook.url
        checkApi(report) { bot.client.getSubscriptions().map { it.url } }.onSuccess { urls ->
            details["subscriptions"] = urls
            if (url != null) {
                details["url"] = url
                details["subscribed"] = url in urls
                if (url !in urls) report.down()
            }
        }
        report.detail("webhook", details)
    }

    /** Calls the API at most once per `cache-ttl`; a failure makes the health down. */
    private fun <T : Any> checkApi(report: HealthReport, call: suspend () -> T): Result<T> {
        @Suppress("UNCHECKED_CAST")
        val result = cachedCall(call) as Result<T>
        result.onFailure {
            report.down().detail("api", "DOWN").detail("apiError", describe(it))
        }.onSuccess {
            report.detail("api", "UP")
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

/** Reachability of the database of `max.bot.storage=jdbc`; throws when the database cannot be reached. */
internal class JdbcStorageHealthCheck(private val dataSource: DataSource, private val timeout: Duration) {
    fun check(): HealthReport = dataSource.connection.use { connection ->
        val report = HealthReport().detail("storage", "jdbc").detail("database", connection.metaData.databaseProductName)
        if (connection.isValid(timeout.toSeconds().toInt().coerceAtLeast(1))) report else report.down()
    }
}

/** Reachability of Redis of `max.bot.storage=redis`; throws when Redis does not answer. */
internal class RedisStorageHealthCheck(
    private val connection: StatefulRedisConnection<String, String>,
    private val timeout: Duration,
) {
    fun check(): HealthReport {
        val pong = connection.async().ping().get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        return HealthReport().detail("storage", "redis").detail("ping", pong)
    }

    companion object {
        /** The check of the Redis connection bean; this class alone refers to Lettuce, so it loads only with it. */
        fun of(beans: BeanFactory, timeout: Duration): RedisStorageHealthCheck? =
            beans.getBeanProvider(StatefulRedisConnection::class.java).ifAvailable?.let {
                @Suppress("UNCHECKED_CAST")
                RedisStorageHealthCheck(it as StatefulRedisConnection<String, String>, timeout)
            }
    }
}

/** The parts of the health contributor `max`: `bot`, and `storage` with `max.bot.storage=jdbc` or `redis`. */
internal class HealthParts(val bot: BotHealthCheck, val storage: (() -> HealthReport)?) {
    companion object {
        fun of(bot: MaxBot, properties: MaxBotProperties, context: ApplicationContext): HealthParts {
            val timeout = properties.health.timeout
            val storage: (() -> HealthReport)? = when (properties.storage) {
                MaxBotProperties.Storage.JDBC ->
                    context.getBeanProvider(DataSource::class.java).ifAvailable?.let { JdbcStorageHealthCheck(it, timeout)::check }
                MaxBotProperties.Storage.REDIS ->
                    if (ClassUtils.isPresent(LETTUCE, context.classLoader)) RedisStorageHealthCheck.of(context, timeout)?.let { it::check } else null
                MaxBotProperties.Storage.MEMORY -> null
            }
            return HealthParts(BotHealthCheck(bot, properties, context.containsBean(WEBHOOK_ENDPOINT)), storage)
        }
    }
}

/** Name of the webhook endpoint bean, servlet or reactive. */
private const val WEBHOOK_ENDPOINT = "maxBotWebhookEndpoint"

private const val LETTUCE = "io.lettuce.core.api.StatefulRedisConnection"

private fun describe(error: Throwable): String = error.message?.let { "${error.javaClass.simpleName}: $it" } ?: error.javaClass.simpleName
