package ru.securitytrip.maxapi.spring

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.WebhookHandler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReactiveWebhookTest {
    private val log = CopyOnWriteArrayList<String>()

    private val runner = ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MaxBotAutoConfiguration::class.java))
        .withPropertyValues("max.bot.token=t", "max.bot.base-url=http://localhost:1/")

    private val update = """{"update_type":"message_created","timestamp":1,"message":{"sender":{"user_id":1},
        "recipient":{"chat_id":5,"chat_type":"dialog"},"timestamp":1,"body":{"mid":"m","seq":1,"text":"hi"}}}"""

    private fun WebTestClient.deliver(path: String, body: String?, secret: String?): WebTestClient.ResponseSpec {
        val request = post().uri(path)
        if (secret != null) request.header(WebhookHandler.SECRET_HEADER, secret)
        return if (body == null) request.exchange() else request.bodyValue(body).exchange()
    }

    @Suppress("UNCHECKED_CAST")
    private fun org.springframework.context.ApplicationContext.client(): WebTestClient =
        WebTestClient.bindToRouterFunction(getBean("maxBotWebhookEndpoint") as RouterFunction<ServerResponse>).build()

    @Test
    fun `the endpoint answers 200, 401 and 400 and waits for the handler`() {
        runner.withPropertyValues("max.bot.mode=webhook", "max.bot.webhook.secret=s3cret")
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { bot -> bot.onMessage { log += "handled on ${Thread.currentThread().name}" } } })
            .run { context ->
                val client = context.client()
                client.deliver("/max/webhook", update, "s3cret").expectStatus().isOk
                // The response came after the handler, which ran off the caller's thread.
                assertEquals(1, log.size)
                assertFalse(log.single().contains("Test worker"), log.single())
                client.deliver("/max/webhook", update, "wrong").expectStatus().isUnauthorized
                client.deliver("/max/webhook", update, null).expectStatus().isUnauthorized
                client.deliver("/max/webhook", "not json", "s3cret").expectStatus().isBadRequest
                client.deliver("/max/webhook", null, "s3cret").expectStatus().isBadRequest
                client.deliver("/elsewhere", update, "s3cret").expectStatus().isNotFound
                assertEquals(1, log.size)
            }
    }

    @Test
    fun `async endpoint answers right away and 503 once the bot is closed`() {
        runner.withPropertyValues("max.bot.mode=webhook", "max.bot.webhook.async=true", "max.bot.webhook.path=/hook")
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { bot -> bot.onMessage { log += "async" } } })
            .run { context ->
                val client = context.client()
                client.deliver("/hook", update, null).expectStatus().isOk
                val deadline = System.currentTimeMillis() + 5_000
                while (log.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(listOf("async"), log)
                client.deliver("/hook", "[]", null).expectStatus().isBadRequest
                context.getBean(MaxBot::class.java).close()
                client.deliver("/hook", update, null).expectStatus().isEqualTo(503)
            }
    }

    @Test
    fun `no endpoint outside webhook mode or in servlet applications`() {
        runner.withPropertyValues("max.bot.mode=none").run { context ->
            assertFalse(context.containsBean("maxBotWebhookEndpoint"))
        }
        org.springframework.boot.test.context.runner.WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaxBotAutoConfiguration::class.java))
            .withPropertyValues("max.bot.token=t", "max.bot.mode=webhook")
            .run { context ->
                // The servlet endpoint, not the reactive one.
                assertEquals(
                    org.springframework.web.servlet.function.RouterFunction::class.java.isInstance(context.getBean("maxBotWebhookEndpoint")),
                    true,
                )
            }
    }
}
