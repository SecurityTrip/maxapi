package ru.securitytrip.maxapi.spring

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator
import org.springframework.boot.actuate.health.AbstractHealthIndicator
import org.springframework.boot.actuate.health.CompositeHealthContributor
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthContributor
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.securitytrip.maxapi.bot.MaxBot

// The health contributor `max` for Spring Boot 3; imported by MaxHealthAutoConfiguration only there.

/** The health contributor `max` with the health API of Spring Boot 3. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnEnabledHealthIndicator("max")
internal class MaxBoot3HealthConfiguration {
    @Bean
    @ConditionalOnBean(MaxBot::class)
    @ConditionalOnMissingBean(name = ["maxHealthContributor"])
    fun maxHealthContributor(bot: MaxBot, properties: MaxBotProperties, context: ApplicationContext): CompositeHealthContributor {
        val parts = HealthParts.of(bot, properties, context)
        val contributors = linkedMapOf<String, HealthContributor>("bot" to Boot3HealthIndicator("Max bot health check failed", parts.bot::check))
        parts.storage?.let { contributors["storage"] = Boot3HealthIndicator("Max bot storage health check failed", it) }
        return CompositeHealthContributor.fromMap(contributors)
    }
}

/** A [HealthReport] as the health of Spring Boot 3; an exception of [check] makes it down. */
internal class Boot3HealthIndicator(description: String, private val check: () -> HealthReport) : AbstractHealthIndicator(description) {
    override fun doHealthCheck(builder: Health.Builder) {
        val report = check()
        if (report.up) builder.up() else builder.down()
        builder.withDetails(report.details)
    }
}
