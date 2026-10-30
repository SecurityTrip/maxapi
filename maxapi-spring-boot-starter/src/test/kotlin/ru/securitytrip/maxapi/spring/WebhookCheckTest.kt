package ru.securitytrip.maxapi.spring

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.RetryPolicy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lifecycle renews a webhook subscription that Max dropped. */
class WebhookCheckTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient
    private val requests = CopyOnWriteArrayList<String>()
    private val subscribed = AtomicInteger()

    /** Subscriptions to answer `GET /subscriptions` with; `null` answers 500. */
    @Volatile
    private var subscriptions: String? = """{"subscriptions":[]}"""

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.url.encodedPath}"
                val body = when (request.method) {
                    "GET" -> subscriptions ?: return MockResponse.Builder().code(500).body("down").build()
                    else -> """{"success":true}""".also { subscribed.incrementAndGet() }
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start()
        client = MaxClient(MaxClientConfig(token = "t", baseUrl = server.url("/").toString(), retryPolicy = RetryPolicy.NONE))
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.close()
    }

    private fun lifecycle(interval: Duration, url: String? = URL): MaxBotLifecycle {
        val properties = MaxBotProperties().apply {
            mode = MaxBotProperties.Mode.WEBHOOK
            webhook.url = url
            webhook.secret = "s3cret"
            webhook.checkInterval = interval
        }
        return MaxBotLifecycle(MaxBot(client).apply { onMessage { } }, properties)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition(), "Timed out; requests: $requests")
    }

    @Test
    fun `a dropped subscription is renewed until the context stops`() {
        val lifecycle = lifecycle(Duration.ofMillis(20))
        lifecycle.start()
        assertTrue(lifecycle.isCheckingWebhook)
        await { subscribed.get() >= 2 }
        subscriptions = """{"subscriptions":[{"url":"$URL","time":1}]}"""
        val renewed = subscribed.get()
        await { requests.count { it == "GET /subscriptions" } >= renewed + 2 }
        assertEquals(renewed, subscribed.get())
        lifecycle.stop()
        assertFalse(lifecycle.isCheckingWebhook)
        val after = requests.size
        Thread.sleep(100)
        assertEquals(after, requests.size)
    }

    @Test
    fun `failed checks are logged and tried again`() {
        subscriptions = null
        val lifecycle = lifecycle(Duration.ofMillis(20))
        lifecycle.start()
        await { requests.count { it == "GET /subscriptions" } >= 2 }
        subscriptions = """{"subscriptions":[]}"""
        await { subscribed.get() >= 2 }
        lifecycle.stop()
    }

    @Test
    fun `no checks with a zero interval or without a URL`() {
        val off = lifecycle(Duration.ZERO)
        off.start()
        assertFalse(off.isCheckingWebhook)
        assertEquals(1, subscribed.get())
        off.stop()
        val manual = lifecycle(Duration.ofMillis(20), url = null)
        manual.start()
        assertFalse(manual.isCheckingWebhook)
        manual.stop()
        Thread.sleep(60)
        assertEquals(listOf("POST /subscriptions"), requests)
    }

    private companion object {
        const val URL = "https://bot.example.com/max/webhook"
    }
}
