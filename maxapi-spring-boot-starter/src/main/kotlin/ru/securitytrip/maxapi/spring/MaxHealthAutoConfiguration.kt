package ru.securitytrip.maxapi.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator
import org.springframework.boot.health.contributor.CompositeHealthContributor
import org.springframework.boot.health.contributor.HealthContributor
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.util.ClassUtils
import ru.securitytrip.maxapi.bot.MaxBot
import javax.sql.DataSource

/**
 * Registers the health contributor `max` when the application has Spring Boot Actuator, so that
 * `/actuator/health/max` shows:
 *
 * - `bot` ([MaxBotHealthIndicator]): whether polling runs or the webhook endpoint is up and subscribed,
 *   and whether the API answers; the API is asked at most once per `max.bot.health.cache-ttl`;
 * - `storage`, with `max.bot.storage=jdbc` or `redis`: whether the database or Redis answers.
 *
 * Turn it off with `management.health.max.enabled=false`, or replace it with your own bean named
 * `maxHealthContributor`.
 */
@AutoConfiguration(after = [MaxBotAutoConfiguration::class, MaxStorageAutoConfiguration::class])
@ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
@ConditionalOnProperty(prefix = "max.bot", name = ["token"])
@ConditionalOnEnabledHealthIndicator("max")
public class MaxHealthAutoConfiguration {
    @Bean
    @ConditionalOnBean(MaxBot::class)
    @ConditionalOnMissingBean(name = ["maxHealthContributor"])
    public fun maxHealthContributor(bot: MaxBot, properties: MaxBotProperties, context: ApplicationContext): CompositeHealthContributor {
        val contributors = linkedMapOf<String, HealthContributor>(
            "bot" to MaxBotHealthIndicator(bot, properties, context.containsBean(WEBHOOK_ENDPOINT)),
        )
        val timeout = properties.health.timeout
        val storage = when (properties.storage) {
            MaxBotProperties.Storage.JDBC ->
                context.getBeanProvider(DataSource::class.java).ifAvailable?.let { JdbcStorageHealthIndicator(it, timeout) }
            MaxBotProperties.Storage.REDIS ->
                if (ClassUtils.isPresent(LETTUCE, context.classLoader)) RedisStorageHealthIndicator.of(context, timeout) else null
            MaxBotProperties.Storage.MEMORY -> null
        }
        if (storage != null) contributors["storage"] = storage
        return CompositeHealthContributor.fromMap(contributors)
    }
}

/** Name of the webhook endpoint bean, servlet or reactive. */
private const val WEBHOOK_ENDPOINT = "maxBotWebhookEndpoint"

private const val LETTUCE = "io.lettuce.core.api.StatefulRedisConnection"
