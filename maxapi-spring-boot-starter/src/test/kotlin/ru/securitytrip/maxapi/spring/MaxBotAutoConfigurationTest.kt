package ru.securitytrip.maxapi.spring

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.springframework.boot.autoconfigure.AutoConfigurations
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.bot.WebhookHandler
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.micrometer.MaxMetrics
import ru.securitytrip.maxapi.client.RetryPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaxBotAutoConfigurationTest {
    private lateinit var server: MockWebServer
    private val log = CopyOnWriteArrayList<String>()

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MaxBotAutoConfiguration::class.java, MaxMetricsAutoConfiguration::class.java))

    private val webRunner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MaxBotAutoConfiguration::class.java))

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) =
        MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    private val SUCCESS_JSON = """{"success":true}"""

    private fun take(): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "No request" }

    private fun props(vararg extra: String) = arrayOf("max.bot.token=t", "max.bot.base-url=${server.url("/")}", *extra)

    private val update = """{"update_type":"message_created","timestamp":1,"message":{"sender":{"user_id":1},
        "recipient":{"chat_id":5,"chat_type":"dialog"},"timestamp":1,"body":{"mid":"m","seq":1,"text":"/hi"}}}"""

    @Configuration(proxyBeanMethods = false)
    open class Handlers(private val log: MutableList<String>) {
        @Bean
        @Order(2)
        open fun second(): MaxBotConfigurer = MaxBotConfigurer { log += "second" }

        @Bean
        @Order(1)
        open fun first(): MaxBotConfigurer = MaxBotConfigurer { bot -> log += "first"; bot.onCommand("hi", "Say hi") { log += "hi" } }

        @Bean
        open fun router(): Router = Router().onMessage { log += "router" }

        @Bean
        open fun retries(): MaxClientConfigCustomizer = MaxClientConfigCustomizer { it.retryPolicy(RetryPolicy.NONE) }
    }

    @Test
    fun `nothing is configured without a token`() {
        runner.run { context ->
            assertFalse(context.containsBean("maxBot"))
            assertTrue(context.getBeansOfType(MaxClient::class.java).isEmpty())
        }
    }

    @Test
    fun `configurers, routers and customizers shape the bot`() {
        runner.withPropertyValues(*props("max.bot.mode=none"))
            .withBean(Handlers::class.java, { Handlers(log) })
            .run { context ->
                val bot = context.getBean(MaxBot::class.java)
                assertEquals(listOf("first", "second"), log)
                assertEquals("hi", bot.commands().single().name)
                bot.processUpdateBlocking(MaxJson.parseUpdate(update))
                bot.processUpdateBlocking(MaxJson.parseUpdate(update.replace("/hi", "text")))
                assertEquals(listOf("first", "second", "hi", "router"), log)
                assertSame(context.getBean(MaxClient::class.java), bot.client)
                assertTrue(context.getBean(MaxBotLifecycle::class.java).isRunning)
            }
    }

    @Test
    fun `polling starts with the context and stops with it`() {
        server.enqueue(json("""{"commands":[]}"""))
        server.enqueue(json("""{"updates":[$update],"marker":1}"""))
        runner.withPropertyValues(*props("max.bot.sync-commands=true"))
            .withBean(BotConfig::class.java, { BotConfig(pollTimeoutSeconds = 0) })
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { bot -> bot.onCommand("hi", "Hi") { log += "handled" } } })
            .run { context ->
                assertEquals("PATCH /me/commands", take().let { "${it.method} ${it.url.encodedPath}" })
                assertEquals("/updates", take().url.encodedPath)
                val deadline = System.currentTimeMillis() + 5_000
                while (log.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(listOf("handled"), log)
                assertTrue(context.getBean(MaxBotLifecycle::class.java).isRunning)
            }
        // The context is closed: the lifecycle stopped polling and waited for it.
    }

    @Test
    fun `webhook mode subscribes and serves the endpoint`() {
        server.enqueue(json("""{"success":true}"""))
        webRunner.withPropertyValues(
            *props("max.bot.mode=webhook", "max.bot.webhook.url=https://bot.example.com/hook", "max.bot.webhook.secret=s3cret"),
        )
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { bot -> bot.onMessage { log += "webhook" } } })
            .run { context ->
                val subscribe = take()
                assertEquals("/subscriptions", subscribe.url.encodedPath)
                assertTrue(subscribe.body!!.utf8().contains("\"secret\":\"s3cret\""))

                @Suppress("UNCHECKED_CAST")
                val route = context.getBean("maxBotWebhookEndpoint") as RouterFunction<ServerResponse>
                val mvc: MockMvc = MockMvcBuilders.routerFunctions(route).build()
                mvc.perform(post("/max/webhook").content(update).header(WebhookHandler.SECRET_HEADER, "s3cret"))
                    .andExpect(status().isOk)
                assertEquals(listOf("webhook"), log)
                mvc.perform(post("/max/webhook").content(update).header(WebhookHandler.SECRET_HEADER, "wrong"))
                    .andExpect(status().isUnauthorized)
                mvc.perform(post("/max/webhook").content("not json").header(WebhookHandler.SECRET_HEADER, "s3cret"))
                    .andExpect(status().isBadRequest)
                context.getBean(MaxBot::class.java).close()
                mvc.perform(post("/max/webhook").content(update).header(WebhookHandler.SECRET_HEADER, "s3cret"))
                    .andExpect(status().isOk)
            }
    }

    @Test
    fun `async webhook without a subscription URL`() {
        webRunner.withPropertyValues(*props("max.bot.mode=webhook", "max.bot.webhook.async=true", "max.bot.webhook.path=/hook"))
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { bot -> bot.onMessage { log += "async" } } })
            .run { context ->
                @Suppress("UNCHECKED_CAST")
                val route = context.getBean("maxBotWebhookEndpoint") as RouterFunction<ServerResponse>
                val mvc = MockMvcBuilders.routerFunctions(route).build()
                mvc.perform(post("/hook").content(update)).andExpect(status().isOk)
                val deadline = System.currentTimeMillis() + 5_000
                while (log.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(listOf("async"), log)
                context.getBean(MaxBot::class.java).close()
                mvc.perform(post("/hook").content(update)).andExpect(status().isServiceUnavailable)
            }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the endpoint needs a web application`() {
        runner.withPropertyValues(*props("max.bot.mode=webhook")).run { context ->
            assertFalse(context.containsBean("maxBotWebhookEndpoint"))
            assertNotNull(context.getBean(MaxBot::class.java))
        }
    }

    @Test
    fun `own beans replace the defaults`() {
        val client = MaxClient("own")
        runner.withPropertyValues(*props("max.bot.mode=none"))
            .withBean(MaxClient::class.java, { client })
            .run { context -> assertSame(client, context.getBean(MaxBot::class.java).client) }
    }

    @Test
    fun `metrics are recorded with a meter registry`() {
        server.enqueue(json(SUCCESS_JSON))
        runner.withPropertyValues(*props("max.bot.mode=none"))
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { bot -> bot.onMessage { client.deleteMessage("m") } } })
            .run { context ->
                assertNotNull(context.getBean(MaxMetrics::class.java))
                context.getBean(MaxBot::class.java).processUpdateBlocking(MaxJson.parseUpdate(update))
                val registry = context.getBean(SimpleMeterRegistry::class.java)
                assertEquals(1, registry.get(MaxMetrics.UPDATES).tag("handled", "true").timer().count())
                assertEquals(1, registry.get(MaxMetrics.REQUESTS).tag("path", "/messages").timer().count())
            }
    }

    @Test
    fun `no metrics without a registry or the metrics module`() {
        runner.withPropertyValues(*props("max.bot.mode=none")).run { context ->
            assertTrue(context.getBeansOfType(MaxMetrics::class.java).isEmpty())
        }
        runner.withPropertyValues(*props("max.bot.mode=none"))
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .withClassLoader(FilteredClassLoader(MaxMetrics::class.java))
            .run { context -> assertFalse(context.containsBean("maxMetrics")) }
    }

    @Test
    fun `properties have defaults`() {
        val properties = MaxBotProperties()
        assertEquals(MaxBotProperties.Mode.POLLING, properties.mode)
        assertEquals("/max/webhook", properties.webhook.path)
        assertFalse(properties.webhook.async)
        assertFalse(properties.syncCommands)
    }
}
