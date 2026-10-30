package ru.securitytrip.maxapi.it

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.WebApplicationType
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.securitytrip.maxapi.it.FakeMax.Companion.message
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Whole Spring Boot applications with the starter: long polling and webhooks against a fake Max
 * server, with dialog states, the marker and seen updates in real PostgreSQL and Redis.
 */
class SpringApplicationIT {
    private val max = FakeMax()
    private val prefix = "it${counter.incrementAndGet()}_"

    @AfterTest
    fun closeServer() {
        max.close()
    }

    private fun properties(vararg extra: String) = arrayOf(
        "max.bot.token=test-token",
        "management.endpoint.health.show-details=always",
        "max.bot.base-url=${max.url}",
        "spring.datasource.url=${postgres.jdbcUrl}",
        "spring.datasource.username=${postgres.username}",
        "spring.datasource.password=${postgres.password}",
        *extra,
    )

    private fun jdbc() = arrayOf("max.bot.storage=jdbc", "max.bot.jdbc.table-prefix=$prefix")

    private fun redis() = arrayOf("max.bot.storage=redis", "max.bot.redis.url=$redisUrl", "max.bot.redis.key-prefix=$prefix:")

    @Test
    fun `a dialog continues after a restart with JDBC storage`() {
        restartInTheMiddleOfADialog(*jdbc())
        // Five updates were delivered (one of them twice); four distinct ones were seen; the dialog ended.
        assertEquals("5", query("SELECT marker FROM ${prefix}bot_marker"))
        assertEquals("4", query("SELECT COUNT(*) FROM ${prefix}seen_update"))
        assertEquals("0", query("SELECT COUNT(*) FROM ${prefix}dialog_state"))
    }

    @Test
    fun `a dialog continues after a restart with Redis storage`() {
        restartInTheMiddleOfADialog(*redis())
    }

    /**
     * Starts the survey, stops the application after the first answer and starts it again: polling
     * continues after the updates already handled, the second answer finds the stored dialog, and
     * a repeated delivery is dropped.
     */
    private fun restartInTheMiddleOfADialog(vararg storage: String) {
        SurveyApp.start(*properties("max.bot.mode=polling", *storage)).use {
            max.deliver(message("m1", "/survey"))
            awaitSent("What is your name?")
            max.deliver(message("m2", "Ann"))
            awaitSent("Nice to meet you, Ann! Rate us from 1 to 5")
        }

        val pollsBefore = max.pollMarkers.size
        SurveyApp.start(*properties("max.bot.mode=polling", *storage)).use {
            await("the restarted bot polls") { max.pollMarkers.size > pollsBefore }
            assertEquals("2", max.pollMarkers[pollsBefore], "polling continues after the two handled updates")

            max.deliver(message("m3", "5"))
            awaitSent("Thanks, Ann: 5")

            // m4 follows the repeated m3 in the same chat, so once m4 is answered the repeat was handled.
            max.deliver(message("m3", "5"))
            max.deliver(message("m4", "hello"))
            awaitSent("echo: hello")
            assertEquals(1, max.sentTexts.count { it == "Thanks, Ann: 5" }, "the repeated delivery is dropped")
        }
    }

    @Test
    fun `webhook deliveries on a real server are handled once`() {
        val url = "https://bot.example/max/webhook"
        val secret = "s3cret-value"
        val webhook = properties(
            "max.bot.mode=webhook", "max.bot.webhook.url=$url", "max.bot.webhook.secret=$secret", "server.port=0", *redis(),
        )
        SurveyApp.start(*webhook, web = WebApplicationType.SERVLET).use { context ->
            val subscription = max.subscriptions.single()
            assertTrue(url in subscription && secret in subscription, subscription)

            val endpoint = URI("http://localhost:${context.environment.getProperty("local.server.port")}/max/webhook")
            fun post(body: String, key: String): Int = http.send(
                HttpRequest.newBuilder(endpoint)
                    .header("X-Max-Bot-Api-Secret", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

            assertEquals(200, post(message("w1", "/survey"), secret))
            assertEquals(listOf("What is your name?"), max.sentTexts, "answered before the response")
            assertEquals(200, post(message("w1", "/survey"), secret), "a retried delivery is accepted")
            assertEquals(1, max.sentTexts.size, "but not handled again")

            assertEquals(401, post(message("w2", "Ann"), "wrong-secret"))
            assertEquals(400, post("not an update", secret))
            assertEquals(200, post(message("w2", "Ann"), secret))
            assertEquals("Nice to meet you, Ann! Rate us from 1 to 5", max.sentTexts.last())
        }
    }

    @Test
    fun `webhook deliveries on a reactive server are handled once`() {
        val url = "https://bot.example/max/webhook"
        val secret = "s3cret-value"
        val webhook = properties(
            "max.bot.mode=webhook", "max.bot.webhook.url=$url", "max.bot.webhook.secret=$secret", "server.port=0", *redis(),
        )
        SurveyApp.start(*webhook, web = WebApplicationType.REACTIVE).use { context ->
            val endpoint = URI("http://localhost:${port(context)}/max/webhook")
            fun post(body: String, key: String): Int = http.send(
                HttpRequest.newBuilder(endpoint)
                    .header("X-Max-Bot-Api-Secret", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

            assertEquals(200, post(message("r1", "/survey"), secret))
            assertEquals(listOf("What is your name?"), max.sentTexts, "answered before the response")
            assertEquals(200, post(message("r1", "/survey"), secret), "a retried delivery is accepted")
            assertEquals(1, max.sentTexts.size, "but not handled again: Redis is asked without blocking")
            assertEquals(401, post(message("r2", "Ann"), "wrong-secret"))
            assertEquals(400, post("not an update", secret))
            assertEquals(200, post(message("r2", "Ann"), secret))
            assertEquals("Nice to meet you, Ann! Rate us from 1 to 5", max.sentTexts.last())

            val health = health(context)
            assertEquals("UP", health.path("status").asText(), health.toString())
            assertEquals(true, health.path("components").path("bot").path("details").path("webhook").path("subscribed").asBoolean())
            assertEquals("PONG", health.path("components").path("storage").path("details").path("ping").asText())
        }
    }

    @Test
    fun `actuator shows the health of a polling bot and its database`() {
        SurveyApp.start(*properties("max.bot.mode=polling", "server.port=0", *jdbc()), web = WebApplicationType.SERVLET).use { context ->
            await("polling starts") { max.pollMarkers.isNotEmpty() }
            val health = health(context)
            assertEquals("UP", health.path("status").asText(), health.toString())
            val bot = health.path("components").path("bot").path("details")
            assertEquals("running", bot.path("polling").asText())
            assertEquals("survey_bot", bot.path("username").asText())
            assertEquals("PostgreSQL", health.path("components").path("storage").path("details").path("database").asText())

            // Health checks within max.bot.health.cache-ttl reuse the answer of the API.
            repeat(3) { health(context) }
            assertEquals(1, max.requests.count { it == "GET /me" })
        }
    }

    private fun port(context: ConfigurableApplicationContext) = context.environment.getProperty("local.server.port")

    /** `GET /actuator/health/max`, with details. */
    private fun health(context: ConfigurableApplicationContext) = ObjectMapper().readTree(
        http.send(
            HttpRequest.newBuilder(URI("http://localhost:${port(context)}/actuator/health/max")).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body(),
    )

    private fun awaitSent(text: String) = await("the bot sends \"$text\"") { text in max.sentTexts }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting until $what; sent: ${max.sentTexts}")
            Thread.sleep(20)
        }
    }

    private fun query(sql: String): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) }
            }
        }

    private companion object {
        val counter = AtomicInteger()
        val http: HttpClient = HttpClient.newHttpClient()
        val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }
        val redis = GenericContainer("redis:7.4-alpine").withExposedPorts(6379).apply { start() }
        val redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
    }
}
