package ru.securitytrip.maxapi.spring

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import ru.securitytrip.maxapi.micrometer.MaxMetrics

/**
 * Records metrics of the auto-configured client and bot ([MaxMetrics]) when `maxapi-micrometer` is
 * on the classpath and the application has a `MeterRegistry`, e.g. from Spring Boot Actuator.
 */
@AutoConfiguration(
    afterName = [
        // Spring Boot 4
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
        // Spring Boot 3
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
    ],
)
@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry", "ru.securitytrip.maxapi.micrometer.MaxMetrics"])
@ConditionalOnProperty(prefix = "max.bot", name = ["token"])
public class MaxMetricsAutoConfiguration {
    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean
    public fun maxMetrics(registry: MeterRegistry): MaxMetrics = MaxMetrics(registry)
}
