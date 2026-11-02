package ru.securitytrip.maxapi.spring

import org.apache.commons.logging.LogFactory
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.core.type.ClassMetadata
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.UnauthorizedException
import java.util.Properties

/** The bot cannot start as configured; [action] tells how to fix it. Explained by [MaxStartupFailureAnalyzer]. */
internal class MaxStartupException(message: String, val action: String) : IllegalStateException(message)

/** Explains a [MaxStartupException] on startup instead of a stack trace. */
internal class MaxStartupFailureAnalyzer : AbstractFailureAnalyzer<MaxStartupException>() {
    override fun analyze(rootFailure: Throwable, cause: MaxStartupException): FailureAnalysis =
        FailureAnalysis(cause.message, cause.action, cause)
}

/** The API rejected the token while the bot started. */
internal class MaxTokenFailureAnalyzer : AbstractFailureAnalyzer<UnauthorizedException>() {
    override fun analyze(rootFailure: Throwable, cause: UnauthorizedException): FailureAnalysis = FailureAnalysis(
        "The Max API rejected max.bot.token: ${cause.message}",
        "Set max.bot.token to the token of the bot from business.max.ru (Chat bots > Integration), " +
            "or get a new token there if this one was revoked.",
        cause,
    )
}

/**
 * The versions of Kotlin, kotlinx.coroutines and Jackson that `maxapi` is built against, compared with
 * the ones the application has. The dependency management of Spring Boot sets the versions of its BOM,
 * older ones included; the library would then fail later with `NoSuchMethodError`.
 */
internal object DependencyVersions {
    /** The versions `maxapi` needs, from the resource its build writes. */
    fun required(): Map<String, String> {
        val properties = Properties()
        MaxClient::class.java.getResourceAsStream("/ru/securitytrip/maxapi/dependencies.properties")?.use(properties::load)
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }

    /** The versions on the classpath; `null` for one that cannot be determined. */
    fun actual(): Map<String, String?> = mapOf(
        "kotlin-stdlib" to KotlinVersion.CURRENT.toString(),
        "kotlinx-coroutines" to kotlinx.coroutines.Job::class.java.getResourceAsStream("/META-INF/kotlinx_coroutines_core.version")
            ?.use { it.readBytes().toString(Charsets.UTF_8).trim() },
        "jackson" to com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString(),
    )

    /** @throws MaxStartupException if the application has an older version of a dependency than [required]. */
    fun check(required: Map<String, String> = required(), actual: Map<String, String?> = actual()) {
        val older = required.filter { (name, version) -> actual[name]?.let { compare(it, version) < 0 } == true }
        if (older.isEmpty()) return
        val list = older.entries.joinToString { (name, version) -> "$name ${actual[name]} (needs $version or newer)" }
        throw MaxStartupException(
            "maxapi needs newer dependencies than the application has: $list",
            "Use the versions maxapi needs, e.g. with the dependency management plugin of Spring Boot: " +
                older.keys.joinToString { "extra[\"${PROPERTIES.getValue(it)}\"] = \"${required.getValue(it)}\"" } +
                " (Maven: the same properties in <properties>), or a newer Spring Boot.",
        )
    }

    /** The version properties of the Spring Boot BOM. */
    private val PROPERTIES = mapOf(
        "kotlin-stdlib" to "kotlin.version",
        "kotlinx-coroutines" to "kotlin-coroutines.version",
        "jackson" to "jackson-2-bom.version",
    )

    /** Compares the numeric parts of two versions, like `2.20.1` and `2.21`. */
    fun compare(a: String, b: String): Int {
        val x = numbers(a)
        val y = numbers(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun numbers(version: String): List<Int> =
        version.substringBefore('-').split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}

/**
 * Matches when `max.bot.token` is set and not blank. A blank token, e.g. from `${MAX_BOT_TOKEN:}` without
 * the variable, turns the bot off with a warning instead of failing the application.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnMaxBotTokenCondition::class)
internal annotation class ConditionalOnMaxBotToken

internal class OnMaxBotTokenCondition : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val token = context.environment.getProperty("max.bot.token") ?: return ConditionOutcome.noMatch("max.bot.token is not set")
        if (token.isNotBlank()) return ConditionOutcome.match("max.bot.token is set")
        // Every auto-configuration of the starter asks; one warning is enough.
        // The annotation is only allowed on classes.
        if ((metadata as ClassMetadata).className == MaxBotAutoConfiguration::class.java.name) {
            log.warn("max.bot.token is blank: the Max bot is turned off")
        }
        return ConditionOutcome.noMatch("max.bot.token is blank")
    }
}

private val log = LogFactory.getLog(MaxBotAutoConfiguration::class.java)
