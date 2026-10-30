package ru.securitytrip.maxapi.spring

import io.opentelemetry.api.OpenTelemetry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import ru.securitytrip.maxapi.opentelemetry.MaxTracing

/**
 * Records spans of the auto-configured client and bot ([MaxTracing]) when `maxapi-opentelemetry` is on
 * the classpath and the application has an `OpenTelemetry` bean, e.g. from Spring Boot's OpenTelemetry
 * support or the OpenTelemetry Spring Boot starter.
 */
@AutoConfiguration(
    afterName = [
        // Spring Boot 4
        "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration",
        // Spring Boot 3
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
        // OpenTelemetry Spring Boot starter
        "io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration",
    ],
)
@ConditionalOnClass(name = ["io.opentelemetry.api.OpenTelemetry", "ru.securitytrip.maxapi.opentelemetry.MaxTracing"])
@ConditionalOnProperty(prefix = "max.bot", name = ["token"])
@EnableConfigurationProperties(MaxBotProperties::class)
public class MaxTracingAutoConfiguration {
    @Bean
    @ConditionalOnBean(OpenTelemetry::class)
    @ConditionalOnMissingBean
    public fun maxTracing(openTelemetry: OpenTelemetry, properties: MaxBotProperties): MaxTracing =
        MaxTracing(openTelemetry, properties.baseUrl)
}
