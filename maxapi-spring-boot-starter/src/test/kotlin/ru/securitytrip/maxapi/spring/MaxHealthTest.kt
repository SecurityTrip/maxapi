package ru.securitytrip.maxapi.spring

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.h2.jdbcx.JdbcDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.CompositeHealthContributor
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ApplicationContext
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.MaxBot
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxHealthTest {
    private lateinit var server: MockWebServer
    private val paths = CopyOnWriteArrayList<String>()

    /** What `GET /subscriptions` answers. */
    @Volatile
    private var subscriptions = """{"subscriptions":[]}"""

    /** What `GET /me` answers. */
    @Volatile
    private var me: MockResponse = json("""{"user_id":1,"first_name":"Bot","username":"test_bot","is_bot":true}""")

    private val configurations = AutoConfigurations.of(
        MaxStorageAutoConfiguration::class.java,
        MaxBotAutoConfiguration::class.java,
        MaxHealthAutoConfiguration::class.java,
    )
    private val runner = ApplicationContextRunner().withConfiguration(configurations)
    private val webRunner = WebApplicationContextRunner().withConfiguration(configurations)

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                paths += path
                return when (path) {
                    "/me" -> me
                    "/subscriptions" -> json(if (request.method == "GET") subscriptions else """{"success":true}""")
                    "/updates" -> json("""{"updates":[],"marker":1}""")
                    else -> json("""{"success":true}""")
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun props(vararg extra: String) = arrayOf("max.bot.token=t", "max.bot.base-url=${server.url("/")}", *extra)

    private fun ApplicationContext.part(name: String): HealthIndicator {
        val contributor = getBean("maxHealthContributor", CompositeHealthContributor::class.java)
        return contributor.getContributor(name) as HealthIndicator
    }

    private fun ApplicationContext.bot(): Health = part("bot").check()

    private fun HealthIndicator.check(): Health = health(true)!!

    private fun count(path: String) = paths.count { it == path }

    @Test
    fun `polling bot is up while polling runs`() {
        runner.withPropertyValues(*props())
            .withBean(BotConfig::class.java, { BotConfig(pollTimeoutSeconds = 0, minPollInterval = Duration.ofMillis(50)) })
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { it.onMessage { } } })
            .run { context ->
                val health = context.bot()
                assertEquals(Status.UP, health.status)
                assertEquals("polling", health.details["mode"])
                assertEquals("running", health.details["polling"])
                assertEquals("UP", health.details["api"])
                assertEquals("test_bot", health.details["username"])
                assertNull(context.getBean("maxHealthContributor", CompositeHealthContributor::class.java).getContributor("storage"))

                context.getBean(MaxBot::class.java).stop()
                while (context.getBean(MaxBot::class.java).isPolling) Thread.sleep(10)
                val stopped = context.bot()
                assertEquals(Status.DOWN, stopped.status)
                assertEquals("stopped", stopped.details["polling"])
            }
    }

    @Test
    fun `polling that ended with an error is down`() {
        me = json("""{"code":"verify.token","message":"Invalid access_token"}""", code = 401)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = me
        }
        runner.withPropertyValues(*props())
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { it.onMessage { }.onError { _, _ -> } } })
            .run { context ->
                val bot = context.getBean(MaxBot::class.java)
                while (bot.pollingFailure == null) Thread.sleep(10)
                val health = context.bot()
                assertEquals(Status.DOWN, health.status)
                assertEquals("failed", health.details["polling"])
                assertEquals("UnauthorizedException: HTTP 401 [verify.token]: Invalid access_token", health.details["pollingError"])
                assertEquals("DOWN", health.details["api"])
                assertTrue((health.details["apiError"] as String).startsWith("UnauthorizedException"))
            }
    }

    @Test
    fun `the API is asked once per cache ttl`() {
        runner.withPropertyValues(*props("max.bot.mode=none")).run { context ->
            repeat(3) { assertEquals(Status.UP, context.bot().status) }
            assertEquals(1, count("/me"))
        }
        runner.withPropertyValues(*props("max.bot.mode=none", "max.bot.health.cache-ttl=0")).run { context ->
            repeat(2) { context.bot() }
            assertEquals(3, count("/me"))
        }
    }

    @Test
    fun `cached results expire, failures too`() {
        val now = AtomicLong()
        val properties = MaxBotProperties().apply { mode = MaxBotProperties.Mode.NONE }
        MaxBot(ru.securitytrip.maxapi.client.MaxClient(
            ru.securitytrip.maxapi.client.MaxClientConfig.builder("t").baseUrl(server.url("/").toString())
                .retryPolicy(ru.securitytrip.maxapi.client.RetryPolicy.NONE).build(),
        )).use { bot ->
            val indicator = MaxBotHealthIndicator(bot, properties, false, now::get)
            me = json("""{"code":"internal","message":"busy"}""", code = 500)
            assertEquals(Status.DOWN, indicator.check().status)
            me = json("""{"user_id":1,"first_name":"Bot"}""")
            assertEquals(Status.DOWN, indicator.check().status, "the failure is reused within the ttl")
            now.set(Duration.ofSeconds(31).toNanos())
            val health = indicator.check()
            assertEquals(Status.UP, health.status)
            assertEquals("", health.details["username"])
            assertEquals(2, count("/me"))
        }
    }

    @Test
    fun `a slow API is reported down after the timeout`() {
        me = json("""{"user_id":1,"first_name":"Bot"}""").newBuilder().headersDelay(2, java.util.concurrent.TimeUnit.SECONDS).build()
        runner.withPropertyValues(*props("max.bot.mode=none", "max.bot.health.timeout=100ms")).run { context ->
            val health = context.bot()
            assertEquals(Status.DOWN, health.status)
            assertTrue((health.details["apiError"] as String).startsWith("TimeoutCancellationException"), health.details.toString())
        }
    }

    @Test
    fun `webhook bot checks the endpoint and the subscription`() {
        subscriptions = """{"subscriptions":[{"url":"https://bot.example/hook","time":1}]}"""
        webRunner.withPropertyValues(*props("max.bot.mode=webhook", "max.bot.webhook.url=https://bot.example/hook", "max.bot.webhook.check-interval=0"))
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { it.onMessage { } } })
            .run { context ->
                val health = context.bot()
                assertEquals(Status.UP, health.status)
                @Suppress("UNCHECKED_CAST")
                val webhook = health.details["webhook"] as Map<String, Any>
                assertEquals("/max/webhook", webhook["path"])
                assertEquals(true, webhook["endpoint"])
                assertEquals(true, webhook["subscribed"])
                assertEquals(listOf("https://bot.example/hook"), webhook["subscriptions"])
            }
        subscriptions = """{"subscriptions":[]}"""
        webRunner.withPropertyValues(*props("max.bot.mode=webhook", "max.bot.webhook.url=https://bot.example/hook", "max.bot.webhook.check-interval=0"))
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { it.onMessage { } } })
            .run { context ->
                val health = context.bot()
                assertEquals(Status.DOWN, health.status)
                assertEquals(false, (health.details["webhook"] as Map<*, *>)["subscribed"])
            }
    }

    @Test
    fun `webhook bot without a URL or an endpoint`() {
        subscriptions = """{"subscriptions":[{"url":"https://elsewhere.example/hook","time":1}]}"""
        webRunner.withPropertyValues(*props("max.bot.mode=webhook")).run { context ->
            val health = context.bot()
            assertEquals(Status.UP, health.status)
            val webhook = health.details["webhook"] as Map<*, *>
            assertFalse(webhook.containsKey("subscribed"))
            assertEquals(listOf("https://elsewhere.example/hook"), webhook["subscriptions"])
        }
        // Not a web application: nothing receives the deliveries.
        runner.withPropertyValues(*props("max.bot.mode=webhook")).run { context ->
            val health = context.bot()
            assertEquals(Status.DOWN, health.status)
            assertEquals(false, (health.details["webhook"] as Map<*, *>)["endpoint"])
        }
        me = json("""{"code":"internal","message":"busy"}""", code = 500)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = me
        }
        webRunner.withPropertyValues(*props("max.bot.mode=webhook", "max.bot.webhook.url=https://bot.example/hook"))
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { it.onMessage { } } })
            .run { context ->
                // Subscribing at startup failed, so the context did not start.
                assertTrue(context.startupFailure != null)
            }
    }

    @Test
    fun `jdbc storage is checked`() {
        val dataSource = JdbcDataSource().apply { setURL("jdbc:h2:mem:health;DB_CLOSE_DELAY=-1") }
        runner.withPropertyValues(*props("max.bot.mode=none", "max.bot.storage=jdbc"))
            .withBean(DataSource::class.java, { dataSource })
            .run { context ->
                val health = context.part("storage").check()
                assertEquals(Status.UP, health.status)
                assertEquals("jdbc", health.details["storage"])
                assertEquals("H2", health.details["database"])
            }
        val invalid = JdbcStorageHealthIndicator(proxyDataSource(valid = false), Duration.ZERO).check()
        assertEquals(Status.DOWN, invalid.status)
        val failing = JdbcStorageHealthIndicator(proxyDataSource(valid = null), Duration.ofSeconds(1)).check()
        assertEquals(Status.DOWN, failing.status)
    }

    /** A data source whose connections are valid or not, or that fails with `valid = null`. */
    private fun proxyDataSource(valid: Boolean?): DataSource {
        val metaData = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseMetaData::class.java)) { _, _, _ -> "Fake" }
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "getMetaData" -> metaData
                "isValid" -> valid
                else -> null
            }
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, _, _ ->
            valid ?: throw java.sql.SQLException("down")
            connection
        } as DataSource
    }

    @Test
    fun `no storage part without its beans or Lettuce`() {
        runner.withPropertyValues(*props("max.bot.mode=none", "max.bot.storage=redis"))
            .withClassLoader(FilteredClassLoader("ru.securitytrip.maxapi.redis"))
            .run { context ->
                assertNull(context.getBean("maxHealthContributor", CompositeHealthContributor::class.java).getContributor("storage"))
            }
        runner.withPropertyValues(*props("max.bot.mode=none", "max.bot.storage=redis"))
            .withClassLoader(FilteredClassLoader("io.lettuce", "ru.securitytrip.maxapi.redis"))
            .run { context ->
                assertNull(context.getBean("maxHealthContributor", CompositeHealthContributor::class.java).getContributor("storage"))
            }
        runner.withPropertyValues(*props("max.bot.mode=none", "max.bot.storage=jdbc"))
            .withClassLoader(FilteredClassLoader("ru.securitytrip.maxapi.jdbc"))
            .run { context ->
                assertNull(context.getBean("maxHealthContributor", CompositeHealthContributor::class.java).getContributor("storage"))
            }
    }

    @Test
    fun `the indicator is optional`() {
        runner.withPropertyValues(*props("max.bot.mode=none", "management.health.max.enabled=false"))
            .run { context -> assertFalse(context.containsBean("maxHealthContributor")) }
        runner.withPropertyValues(*props("max.bot.mode=none"))
            .withClassLoader(FilteredClassLoader(HealthIndicator::class.java))
            .run { context -> assertFalse(context.containsBean("maxHealthContributor")) }
        runner.run { context -> assertFalse(context.containsBean("maxHealthContributor")) }
        val properties = MaxBotProperties()
        assertEquals(Duration.ofSeconds(30), properties.health.cacheTtl)
        assertEquals(Duration.ofSeconds(5), properties.health.timeout)
    }
}
