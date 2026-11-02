package ru.securitytrip.maxapi.spring

import org.springframework.boot.health.contributor.CompositeHealthContributor
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.ApplicationContext

// The health API of Spring Boot 4, for the tests of src/test; src/testBoot3 has the same for Spring Boot 3.

/** A health of the version of Spring Boot the tests run on. */
data class HealthView(val status: String, val details: Map<String, Any?>)

/** The part [name] of the health contributor `max`; `null` without it. */
fun ApplicationContext.healthPart(name: String): HealthView? {
    val part = getBean("maxHealthContributor", CompositeHealthContributor::class.java).getContributor(name) as HealthIndicator?
    return part?.health(true)?.let { HealthView(it.status.code, it.details) }
}

/** The class without which the health contributor is not registered. */
val HEALTH_INDICATOR: Class<*> = HealthIndicator::class.java
