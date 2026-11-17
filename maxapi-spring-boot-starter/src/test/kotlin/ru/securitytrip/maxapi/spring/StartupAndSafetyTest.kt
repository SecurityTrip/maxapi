package ru.securitytrip.maxapi.spring

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.InMemoryUpdateDeduplicator
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.SuspendUpdateDeduplicator
import ru.securitytrip.maxapi.bot.WebhookHandler
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.client.UnauthorizedException
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.jdbc.JdbcStateStorage
import ru.securitytrip.maxapi.redis.RedisStateStorage
import ru.securitytrip.maxapi.spring.test.MaxBotTest
import java.net.Proxy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.web.reactive.function.server.RouterFunction as ReactiveRouterFunction
import org.springframework.web.reactive.function.server.ServerResponse as ReactiveServerResponse

/** Startup, shutdown and safety of the starter: tokens, secrets, body limits, storages, versions and settings. */
class StartupAndSafetyTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()

    /** Answers by path; `null` answers `{"success":true}`. */
    @Volatile
    private var answer: (RecordedRequest) -> MockResponse? = { null }

    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MaxBotAutoConfiguration::class.java))

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.url.encodedPath}"
                return answer(request) ?: when (request.url.encodedPath) {
                    "/me" -> json(ME)
                    "/subscriptions" -> json(if (request.method == "GET") """{"subscriptions":[]}""" else SUCCESS)
                    "/updates" -> json("""{"updates":[],"marker":1}""")
                    else -> json(SUCCESS)
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

    private fun client() = MaxClient(MaxClientConfig(token = "t", baseUrl = server.url("/").toString(), retryPolicy = RetryPolicy.NONE))

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition(), "Timed out; requests: $requests")
    }

    // ------------------------------------------------------------ token

    @Test
    fun `a blank token turns the bot off`() {
        runner.withConfiguration(AutoConfigurations.of(MaxHealthAutoConfiguration::class.java, MaxWebAppAutoConfiguration::class.java))
            .withPropertyValues("max.bot.token=  ")
            .run { context ->
                assertNull(context.startupFailure)
                assertFalse(context.containsBean("maxBot"))
                assertFalse(context.containsBean("maxHealthContributor"))
            }
    }

    @Test
    fun `a rejected token fails the start with an explanation`() {
        answer = { json("""{"code":"verify.token","message":"Invalid access_token"}""", code = 401) }
        runner.withPropertyValues(*props()).run { context ->
            val failure = assertNotNull(context.startupFailure)
            val analysis = assertNotNull(MaxTokenFailureAnalyzer().analyze(failure))
            assertTrue(analysis.description!!.contains("max.bot.token"), analysis.description)
            assertTrue(analysis.action!!.contains("business.max.ru"))
            assertIs<UnauthorizedException>(analysis.cause)
        }
        assertEquals(listOf("GET /me"), requests)
    }

    @Test
    fun `nothing calls the API on startup in mode none without menu sync`() {
        runner.withPropertyValues(*props("max.bot.mode=none")).run { context ->
            assertTrue(context.getBean(MaxBotLifecycle::class.java).isRunning)
            assertFalse(context.getBean(MaxBotLifecycle::class.java).isPrepared)
        }
        assertEquals(emptyList(), requests)
    }

    // ------------------------------------------------------------ lifecycle

    private fun lifecycle(vararg mode: MaxBotProperties.Mode, configure: MaxBotProperties.() -> Unit = {}): Pair<MaxBotLifecycle, MaxBot> {
        val properties = MaxBotProperties().apply {
            this.mode = mode.firstOrNull() ?: MaxBotProperties.Mode.NONE
            syncCommands = true
            configure()
        }
        val bot = MaxBot(client(), BotConfig(pollTimeoutSeconds = 0, minPollInterval = Duration.ofMillis(10))).apply { onMessage { } }
        return MaxBotLifecycle(bot, properties).apply { retryDelay = Duration.ofMillis(10) } to bot
    }

    @Test
    fun `startup steps are retried in the background while the API is down`() {
        val failures = AtomicInteger(2)
        answer = { if (it.url.encodedPath == "/me/commands" && failures.getAndDecrement() > 0) json("""{"code":"down"}""", 503) else null }
        val (lifecycle, bot) = lifecycle()
        lifecycle.start()
        assertTrue(lifecycle.isRunning)
        assertEquals("my_bot", bot.username)
        await { lifecycle.isPrepared }
        assertEquals(listOf("GET /me", "PATCH /me/commands", "PATCH /me/commands", "PATCH /me/commands"), requests)
        lifecycle.stop()
        bot.close()
    }

    @Test
    fun `a slow API does not hold up the start`() {
        val first = AtomicInteger()
        answer = {
            if (it.url.encodedPath == "/me" && first.getAndIncrement() == 0) json(ME).newBuilder().headersDelay(3, TimeUnit.SECONDS).build() else null
        }
        val (lifecycle, bot) = lifecycle { startupTimeout = Duration.ofMillis(200) }
        val started = System.nanoTime()
        lifecycle.start()
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2))
        assertFalse(lifecycle.isPrepared)
        await { lifecycle.isPrepared }
        lifecycle.stop()
        bot.close()
    }

    @Test
    fun `no retries once the lifecycle stopped`() {
        val (lifecycle, bot) = lifecycle()
        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        executor.shutdownNow()
        lifecycle.retry(executor, 1)
        assertEquals(emptyList(), requests)
        bot.close()
    }

    @Test
    fun `a token rejected later stops the retries`() {
        val calls = AtomicInteger()
        answer = {
            when (calls.getAndIncrement()) {
                0 -> json("""{"code":"down"}""", 503)
                else -> json("""{"code":"verify.token"}""", 401)
            }
        }
        val (lifecycle, bot) = lifecycle()
        lifecycle.start()
        await { requests.size >= 2 }
        Thread.sleep(100)
        assertEquals(2, requests.size)
        assertFalse(lifecycle.isPrepared)
        lifecycle.stop()
        bot.close()
    }

    @Test
    fun `stopping lets received updates finish and runs the callback after them`() {
        val (lifecycle, bot) = lifecycle(MaxBotProperties.Mode.POLLING) { syncCommands = false }
        lifecycle.start()
        await { requests.contains("GET /updates") }
        val stopped = CountDownLatch(1)
        lifecycle.stop { stopped.countDown() }
        assertFalse(lifecycle.isRunning)
        assertTrue(stopped.await(5, TimeUnit.SECONDS))
        assertFalse(bot.isPolling)
        // Without polling, the callback runs at once.
        val (idle, idleBot) = lifecycle { syncCommands = false }
        idle.start()
        var called = false
        idle.stop { called = true }
        assertTrue(called)
        idleBot.close()
        bot.close()
    }

    @Test
    fun `stop gives up waiting after the shutdown timeout`() {
        val page = """{"updates":[{"update_type":"message_created","timestamp":1,"message":{"sender":{"user_id":1},
            "recipient":{"chat_id":5,"chat_type":"dialog"},"timestamp":1,"body":{"mid":"m","seq":1,"text":"hi"}}}],"marker":2}"""
        answer = { if (it.url.encodedPath == "/updates") json(page) else null }
        val properties = MaxBotProperties().apply { shutdownTimeout = Duration.ofMillis(100) }
        val bot = MaxBot(client(), BotConfig(pollTimeoutSeconds = 0, handlerTimeout = null)).apply { onMessage { kotlinx.coroutines.awaitCancellation() } }
        val lifecycle = MaxBotLifecycle(bot, properties)
        lifecycle.start()
        await { requests.contains("GET /updates") }
        Thread.sleep(50)
        val started = System.nanoTime()
        lifecycle.stop()
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
        bot.close()
    }

    // ------------------------------------------------------------ webhook

    @Test
    fun `the webhook subscribes with a secret derived from the token and checks it before the body`() {
        val secret = WebhookHandler.secretFor("t")
        WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(MaxBotAutoConfiguration::class.java))
            .withPropertyValues(*props("max.bot.mode=webhook", "max.bot.webhook.url=https://bot.example.com/hook", "max.bot.webhook.max-body-size=1KB"))
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { it.onMessage { } } })
            .run { context ->
                assertNull(context.startupFailure)
                await { requests.contains("POST /subscriptions") }
                @Suppress("UNCHECKED_CAST")
                val mvc = MockMvcBuilders.routerFunctions(context.getBean("maxBotWebhookEndpoint") as RouterFunction<ServerResponse>).build()
                val big = "x".repeat(2000)
                mvc.perform(post("/max/webhook").content(big)).andExpect(status().isUnauthorized)
                mvc.perform(post("/max/webhook").content(big).header(WebhookHandler.SECRET_HEADER, secret)).andExpect(status().isPayloadTooLarge)
                // A body sent in chunks has no length: it is read up to the limit.
                val chunked = object : org.springframework.mock.web.MockHttpServletRequest("POST", "/max/webhook") {
                    override fun getContentLengthLong(): Long = -1
                }
                chunked.setContent(big.toByteArray())
                chunked.addHeader(WebhookHandler.SECRET_HEADER, secret)
                @Suppress("UNCHECKED_CAST")
                val route = context.getBean("maxBotWebhookEndpoint") as RouterFunction<ServerResponse>
                val request = org.springframework.web.servlet.function.ServerRequest.create(chunked, emptyList())
                assertEquals(413, route.route(request).get().handle(request).statusCode().value())
                mvc.perform(post("/max/webhook").content("not json").header(WebhookHandler.SECRET_HEADER, secret)).andExpect(status().isBadRequest)
            }
        assertTrue(requests.contains("GET /me"))
    }

    @Test
    fun `the reactive webhook checks the secret and limits the body`() {
        val secret = WebhookHandler.secretFor("t")
        ReactiveWebApplicationContextRunner().withConfiguration(AutoConfigurations.of(MaxBotAutoConfiguration::class.java))
            .withPropertyValues(*props("max.bot.mode=webhook", "max.bot.webhook.max-body-size=1KB"))
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { it.onMessage { } } })
            .run { context ->
                @Suppress("UNCHECKED_CAST")
                val client = WebTestClient.bindToRouterFunction(context.getBean("maxBotWebhookEndpoint") as ReactiveRouterFunction<ReactiveServerResponse>).build()
                val big = "x".repeat(2000)
                client.post().uri("/max/webhook").bodyValue(big).exchange().expectStatus().isUnauthorized
                client.post().uri("/max/webhook").header(WebhookHandler.SECRET_HEADER, secret).bodyValue(big).exchange().expectStatus().isEqualTo(413)
                // Without a length, the body is joined up to the limit.
                client.post().uri("/max/webhook").header(WebhookHandler.SECRET_HEADER, secret)
                    .body(reactor.core.publisher.Flux.just(big, big).map { org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(it.toByteArray()) }, org.springframework.core.io.buffer.DataBuffer::class.java)
                    .exchange().expectStatus().isEqualTo(413)
            }
    }

    @Test
    fun `the body limit must fit in memory`() {
        val webhook = MaxBotProperties.Webhook()
        assertEquals(1024 * 1024, bodyLimit(webhook))
        webhook.maxBodySize = org.springframework.util.unit.DataSize.ofBytes(0)
        assertFailsWith<IllegalArgumentException> { bodyLimit(webhook) }
        webhook.maxBodySize = org.springframework.util.unit.DataSize.ofGigabytes(4)
        assertFailsWith<IllegalArgumentException> { bodyLimit(webhook) }
        val properties = MaxBotProperties().apply { token = "t" }
        assertEquals(WebhookHandler.secretFor("t"), properties.webhookSecret())
        properties.webhook.secret = "own"
        assertEquals("own", properties.webhookSecret())
    }

    // ------------------------------------------------------------ storages

    @Test
    fun `a storage without its module fails the start`() {
        for ((storage, hidden) in listOf("jdbc" to JdbcStateStorage::class.java, "redis" to RedisStateStorage::class.java)) {
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MaxStorageAutoConfiguration::class.java, MaxBotAutoConfiguration::class.java))
                .withClassLoader(FilteredClassLoader(hidden))
                .withPropertyValues(*props("max.bot.mode=none", "max.bot.storage=$storage"))
                .run { context ->
                    val failure = assertNotNull(context.startupFailure)
                    val analysis = assertNotNull(MaxStartupFailureAnalyzer().analyze(failure))
                    assertTrue(analysis.description!!.contains("max.bot.storage is $storage"), analysis.description)
                    assertTrue(analysis.action!!.contains("maxapi-$storage"))
                }
        }
        // No token, no bot: the storage setting does not matter then.
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaxStorageAutoConfiguration::class.java, MaxBotAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader(JdbcStateStorage::class.java))
            .withPropertyValues("max.bot.storage=jdbc")
            .run { context -> assertNull(context.startupFailure) }
    }

    @Test
    fun `failure analyzers are registered`() {
        val analyzers = SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer::class.java, javaClass.classLoader)
        assertTrue(analyzers.containsAll(listOf(MaxStartupFailureAnalyzer::class.java.name, MaxTokenFailureAnalyzer::class.java.name)), "$analyzers")
    }

    // ------------------------------------------------------------ versions

    @Test
    fun `older dependencies than maxapi needs fail the start`() {
        val required = DependencyVersions.required()
        assertEquals(setOf("kotlin-stdlib", "kotlinx-coroutines", "jackson"), required.keys)
        val actual = DependencyVersions.actual()
        assertTrue(actual.values.all { it != null }, "$actual")
        DependencyVersions.check() // the test classpath has what maxapi needs

        val error = assertFailsWith<MaxStartupException> {
            DependencyVersions.check(
                mapOf("kotlin-stdlib" to "2.2.21", "kotlinx-coroutines" to "1.11.0", "jackson" to "2.20.1"),
                mapOf("kotlin-stdlib" to "2.3.0", "kotlinx-coroutines" to "1.10.2", "jackson" to null),
            )
        }
        assertTrue(error.message!!.contains("kotlinx-coroutines 1.10.2 (needs 1.11.0 or newer)"), error.message)
        assertTrue(error.action.contains("extra[\"kotlin-coroutines.version\"] = \"1.11.0\""), error.action)
        val jackson = assertFailsWith<MaxStartupException> {
            DependencyVersions.check(mapOf("jackson" to "9.0.0"), mapOf("jackson" to "2.18.1"))
        }
        val property = if (org.springframework.boot.SpringBootVersion.getVersion().startsWith("3.")) "jackson-bom.version" else "jackson-2-bom.version"
        assertTrue(jackson.action.contains("extra[\"$property\"]"), jackson.action)
        assertEquals(0, DependencyVersions.compare("2.20", "2.20.0"))
        assertTrue(DependencyVersions.compare("2.20.1", "2.21") < 0)
        assertTrue(DependencyVersions.compare("1.10.2-RC", "1.10.1") > 0)
        assertTrue(DependencyVersions.compare("2.x", "2.0") == 0)
        assertTrue(DependencyVersions.compare("2.20.1", "2.20") > 0)
    }

    // ------------------------------------------------------------ settings

    @Test
    fun `bot settings come from properties and customizers`() {
        runner.withPropertyValues(
            *props(
                "max.bot.mode=none", "max.bot.parallelism=8", "max.bot.queue-capacity=3", "max.bot.handler-timeout=0",
                "max.bot.poll-timeout=5s", "max.bot.auto-answer-callbacks=false",
            ),
        )
            .withBean(BotConfigCustomizer::class.java, { BotConfigCustomizer { it.pollLimit(7) } })
            .run { context ->
                val config = context.getBean(BotConfig::class.java)
                assertEquals(8, config.parallelism)
                assertEquals(3, config.queueCapacity)
                assertNull(config.handlerTimeout)
                assertEquals(5, config.pollTimeoutSeconds)
                assertFalse(config.autoAnswerCallbacks)
                assertEquals(7, config.pollLimit)
                assertIs<InMemoryUpdateDeduplicator>(config.deduplicator)
            }
        runner.withPropertyValues(*props("max.bot.mode=none")).run { context ->
            val config = context.getBean(BotConfig::class.java)
            assertEquals(Duration.ofMinutes(1), config.handlerTimeout)
            assertTrue(config.autoAnswerCallbacks)
        }
        val suspending = SuspendUpdateDeduplicator { true }
        runner.withPropertyValues(*props("max.bot.mode=none"))
            .withBean(SuspendUpdateDeduplicator::class.java, { suspending })
            .run { context ->
                val config = context.getBean(BotConfig::class.java)
                assertNull(config.deduplicator)
                assertSame(suspending, config.suspendDeduplicator)
            }
    }

    @Test
    fun `factory methods of earlier versions still build settings`() {
        val configuration = MaxBotAutoConfiguration()
        val provider = org.springframework.beans.factory.support.StaticListableBeanFactory()
        fun <T : Any> empty(type: Class<T>) = provider.getBeanProvider(type)
        val types = listOf(MaxEventListener::class.java, ru.securitytrip.maxapi.bot.StateStorage::class.java,
            ru.securitytrip.maxapi.bot.MarkerStore::class.java, ru.securitytrip.maxapi.bot.UpdateDeduplicator::class.java,
            ru.securitytrip.maxapi.bot.ChatStore::class.java, SuspendUpdateDeduplicator::class.java)
        for (count in 4..6) {
            val method = MaxBotAutoConfiguration::class.java.getMethod("maxBotConfig", *Array(count) { org.springframework.beans.factory.ObjectProvider::class.java })
            val config = method.invoke(configuration, *types.take(count).map { empty(it) }.toTypedArray()) as BotConfig
            assertIs<InMemoryUpdateDeduplicator>(config.deduplicator)
        }
    }

    @Test
    fun `the HTTP client has the timeouts and the proxy of the properties`() {
        val plain = httpClientBuilder(MaxBotProperties().apply { connectTimeout = Duration.ofSeconds(3); readTimeout = Duration.ofSeconds(4) }).build()
        assertEquals(3_000, plain.connectTimeoutMillis)
        assertEquals(4_000, plain.readTimeoutMillis)
        assertNull(plain.proxy)
        assertNull(httpClientBuilder(MaxBotProperties().apply { proxy = " " }).build().proxy)
        val socks = httpClientBuilder(MaxBotProperties().apply { proxy = "socks5://proxy.local:1080" }).build()
        assertEquals(Proxy.Type.SOCKS, socks.proxy!!.type())
        assertFailsWith<IllegalArgumentException> { httpClientBuilder(MaxBotProperties().apply { proxy = "http://proxy.local" }) }
        assertFailsWith<IllegalArgumentException> { httpClientBuilder(MaxBotProperties().apply { proxy = "http:///no-host" }) }
        assertFailsWith<IllegalArgumentException> { httpClientBuilder(MaxBotProperties().apply { proxy = "//proxy.local:3128" }) }
        assertFailsWith<IllegalArgumentException> { httpClientBuilder(MaxBotProperties().apply { proxy = "ftp://proxy.local:21" }) }
        val credentials = assertFailsWith<IllegalArgumentException> {
            httpClientBuilder(MaxBotProperties().apply { proxy = "socks5://user:pass@proxy.local:1080" })
        }
        assertTrue(credentials.message!!.contains("only for http"))
        assertEquals(Duration.ofSeconds(10), MaxBotProperties().startupTimeout)
    }

    @Test
    fun `requests go through an authenticating proxy`() {
        val proxied = CopyOnWriteArrayList<String?>()
        answer = { request ->
            proxied += request.headers["Proxy-Authorization"]
            if (request.headers["Proxy-Authorization"] == null) {
                MockResponse.Builder().code(407).addHeader("Proxy-Authenticate", "Basic realm=\"proxy\"").build()
            } else {
                json(ME)
            }
        }
        // Through a proxy, https to the API would be tunnelled; the mock proxy answers plain http requests itself.
        val properties = MaxBotProperties().apply { proxy = "http://user%40corp:p%3Ass@localhost:${server.port}" }
        val http = httpClientBuilder(properties).build()
        val response = http.newCall(okhttp3.Request.Builder().url("http://api.test/me").build()).execute()
        assertEquals(200, response.code)
        response.close()
        assertEquals(listOf(null, okhttp3.Credentials.basic("user@corp", "p:ss")), proxied)

        // Wrong credentials are not tried forever.
        answer = { MockResponse.Builder().code(407).addHeader("Proxy-Authenticate", "Basic").build() }
        val denied = http.newCall(okhttp3.Request.Builder().url("http://api.test/me").build()).execute()
        assertEquals(407, denied.code)
        denied.close()

        proxied.clear()
        answer = { request ->
            proxied += request.headers["Proxy-Authorization"]
            if (request.headers["Proxy-Authorization"] == null) MockResponse.Builder().code(407).build() else json(ME)
        }
        val noPassword = httpClientBuilder(MaxBotProperties().apply { proxy = "http://user@localhost:${server.port}" }).build()
        noPassword.newCall(okhttp3.Request.Builder().url("http://api.test/me").build()).execute().close()
        assertEquals(okhttp3.Credentials.basic("user", ""), proxied.last())
    }

    private companion object {
        const val ME = """{"user_id":9,"first_name":"Bot","username":"my_bot","is_bot":true}"""
        const val SUCCESS = """{"success":true}"""
    }
}

/** `@MaxBotTest` starts the context of an application without a token of its own and without the network. */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [MaxBotAutoConfiguration::class])
@MaxBotTest
class MaxBotTestAnnotationTest {
    @Autowired
    private lateinit var bot: MaxBot

    @Autowired
    private lateinit var properties: MaxBotProperties

    @Test
    fun `the bot is configured but not connected`() {
        assertEquals("test-token", bot.client.config.token)
        assertEquals(MaxBotProperties.Mode.NONE, properties.mode)
        assertFalse(properties.syncCommands)
        assertNull(bot.username)
    }
}
