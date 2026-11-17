package ru.securitytrip.maxapi.spring

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.CompositeHealthContributor
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthContributor
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.securitytrip.maxapi.bot.MaxBot

// The health contributor `max` for Spring Boot 4, compiled against it in the boot4 source set and
// imported by MaxHealthAutoConfiguration only there.

/** The health contributor `max` with the health API of Spring Boot 4. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnEnabledHealthIndicator("max")
internal class MaxBoot4HealthConfiguration {
    @Bean
    @ConditionalOnBean(MaxBot::class)
    @ConditionalOnMissingBean(name = ["maxHealthContributor"])
    fun maxHealthContributor(bot: MaxBot, properties: MaxBotProperties, context: ApplicationContext): CompositeHealthContributor {
        val parts = HealthParts.of(bot, properties, context)
        val contributors = linkedMapOf<String, HealthContributor>("bot" to MaxBotHealthIndicator(parts.bot::check))
        parts.storage?.let { contributors["storage"] = StorageHealthIndicator(it) }
        return CompositeHealthContributor.fromMap(contributors)
    }
}

/**
 * Health of the bot, the `bot` part of the health contributor `max` in Spring Boot 4: whether polling
 * runs or the webhook endpoint is up and subscribed, and whether the API answers.
 */
public class MaxBotHealthIndicator internal constructor(private val check: () -> HealthReport) :
    AbstractHealthIndicator("Max bot health check failed") {
    override fun doHealthCheck(builder: Health.Builder): Unit = builder.report(check())
}

/** The `storage` part of the health contributor `max` in Spring Boot 4; an exception of [check] makes it down. */
internal class StorageHealthIndicator(private val check: () -> HealthReport) : AbstractHealthIndicator("Max bot storage health check failed") {
    override fun doHealthCheck(builder: Health.Builder) = builder.report(check())
}

private fun Health.Builder.report(report: HealthReport) {
    if (report.up) up() else down()
    withDetails(report.details)
}
