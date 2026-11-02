package ru.securitytrip.maxapi.spring

import org.springframework.beans.factory.BeanClassLoaderAware
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportSelector
import org.springframework.core.type.AnnotationMetadata
import org.springframework.util.ClassUtils

/**
 * Registers the health contributor `max` when the application has Spring Boot Actuator, so that
 * `/actuator/health/max` shows:
 *
 * - `bot`: whether polling runs or the webhook endpoint is up and subscribed, and whether the API
 *   answers; the API is asked at most once per `max.bot.health.cache-ttl`;
 * - `storage`, with `max.bot.storage=jdbc` or `redis`: whether the database or Redis answers.
 *
 * Spring Boot 4 and 3 keep their health API in different packages; the configuration for the version on
 * the classpath is imported, and the other one is never loaded.
 *
 * Turn it off with `management.health.max.enabled=false`, or replace it with your own bean named
 * `maxHealthContributor`.
 */
@AutoConfiguration(after = [MaxBotAutoConfiguration::class, MaxStorageAutoConfiguration::class])
@ConditionalOnMaxBotToken
@Import(MaxHealthAutoConfiguration.Selector::class)
public class MaxHealthAutoConfiguration {
    /** Imports the health configuration for the Spring Boot on the classpath, by class name. */
    internal class Selector : ImportSelector, BeanClassLoaderAware {
        private var classLoader: ClassLoader? = null

        override fun setBeanClassLoader(classLoader: ClassLoader) {
            this.classLoader = classLoader
        }

        override fun selectImports(metadata: AnnotationMetadata): Array<String> = when {
            ClassUtils.isPresent(BOOT4_HEALTH, classLoader) -> arrayOf("ru.securitytrip.maxapi.spring.MaxBoot4HealthConfiguration")
            ClassUtils.isPresent(BOOT3_HEALTH, classLoader) -> arrayOf("ru.securitytrip.maxapi.spring.MaxBoot3HealthConfiguration")
            else -> emptyArray()
        }
    }
}

/** The health indicator of Spring Boot 4 (`spring-boot-health`). */
private const val BOOT4_HEALTH = "org.springframework.boot.health.contributor.HealthIndicator"

/** The health indicator of Spring Boot 3 (`spring-boot-actuator`). */
private const val BOOT3_HEALTH = "org.springframework.boot.actuate.health.HealthIndicator"
