package ru.securitytrip.maxapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import ru.securitytrip.maxapi.bot.BlockingMarkerStore
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.FileMarkerStore
import ru.securitytrip.maxapi.bot.Handler
import ru.securitytrip.maxapi.bot.HandlerTimeoutException
import ru.securitytrip.maxapi.bot.InMemoryMarkerStore
import ru.securitytrip.maxapi.bot.InMemoryUpdateDeduplicator
import ru.securitytrip.maxapi.bot.MarkerStore
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.UpdateDeduplicator
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.MaxNetworkException
import ru.securitytrip.maxapi.client.RateLimit
import ru.securitytrip.maxapi.client.RateLimiter
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.client.TokenBucket
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.events.RequestEvent
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.model.update.Update
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityTest {
    private lateinit var server: MockWebServer
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val requests = CopyOnWriteArrayList<RequestEvent>()
    private val updates = CopyOnWriteArrayList<UpdateEvent>()

    private val listener = object : MaxEventListener {
        override fun onRequest(event: RequestEvent) {
            requests += event
        }

        override fun onUpdate(event: UpdateEvent) {
            updates += event
        }
    }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun client(rateLimit: RateLimit? = null, events: MaxEventListener = listener) = MaxClient(
        MaxClientConfig.builder(TOKEN)
            .baseUrl(server.url("/").toString())
            .retryPolicy(RetryPolicy(maxRetries = 1, initialDelay = Duration.ofMillis(1)))
            .rateLimit(rateLimit)
            .eventListener(events)
            .build(),
    )

    private fun message(text: String, chatId: Long = 100, mid: String = "mid.$text"): Update =
        MaxJson.parseUpdate("""{"update_type":"message_created","timestamp":1,"message":${messageJson(chatId, text, mid)}}""")

    // ---------------------------------------------------------- rate limit

    @Test
    fun `rate limit settings are validated`() {
        assertFailsWith<IllegalArgumentException> { RateLimit(0.0) }
        assertFailsWith<IllegalArgumentException> { RateLimit(1.0, burst = 0) }
        assertFailsWith<IllegalArgumentException> { RateLimit(1.0, perChatRequestsPerSecond = 0.0) }
        assertFailsWith<IllegalArgumentException> { RateLimit(1.0, perChatBurst = 0) }
        assertEquals(1, RateLimit(0.5).burst)
        assertEquals(30, RateLimit(30.0).burst)
        assertEquals(
            "RateLimit(requestsPerSecond=2.0, burst=2, perChatRequestsPerSecond=1.0, perChatBurst=1)",
            RateLimit(2.0, perChatRequestsPerSecond = 1.0).toString(),
        )
    }

    @Test
    fun `a token bucket allows a burst, then the rate`() {
        var now = 0L
        val bucket = TokenBucket(ratePerSecond = 10.0, capacity = 2) { now }
        assertEquals(0, bucket.reserve())
        assertEquals(0, bucket.reserve())
        assertEquals(100_000_000, bucket.reserve())
        assertEquals(200_000_000, bucket.reserve())
        now += 1_000_000_000 // one second later the debt is paid and one token is back
        assertEquals(0, bucket.reserve())
        now += 10_000_000_000
        assertEquals(0, bucket.reserve()) // the bucket never holds more than its capacity
        assertEquals(0, bucket.reserve())
        assertEquals(100_000_000, bucket.reserve())
    }

    @Test
    fun `requests wait for the limiter in virtual time`() = runTest {
        val limiter = RateLimiter(RateLimit(requestsPerSecond = 10.0, burst = 1, perChatRequestsPerSecond = 1.0)) {
            currentTime * 1_000_000
        }
        val log = mutableListOf<String>()
        launch { repeat(3) { limiter.acquire(null); log += "global ${currentTime}" } }
        launch { repeat(2) { limiter.acquire(7); log += "chat ${currentTime}" } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("global 0", "global 100", "chat 200", "global 300", "chat 1000"), log)
        assertEquals(1, limiter.trackedChats)
    }

    @Test
    fun `idle chats are forgotten`() = runTest {
        val limiter = RateLimiter(RateLimit(1e9, burst = 1_000_000, perChatRequestsPerSecond = 1.0)) { 0 }
        for (chat in 0L..RateLimiter.MAX_CHATS) limiter.acquire(chat)
        assertEquals(RateLimiter.MAX_CHATS, limiter.trackedChats)
    }

    @Test
    fun `the client applies the rate limit to every request`(): Unit = runBlocking {
        repeat(4) { server.enqueue(json(SUCCESS)) }
        server.enqueue(json("""{"subscriptions":[]}"""))
        server.enqueue(json("""{"message":${messageJson()}}"""))
        client(RateLimit(1000.0, perChatRequestsPerSecond = 1000.0, perChatBurst = 10)).use { client ->
            client.sendAction(5, ru.securitytrip.maxapi.model.chat.SenderAction.TYPING_ON)
            client.deleteMessage("mid.1")
            client.pinMessage(6, "mid.2")
            client.unpinMessage(6)
            client.getSubscriptions()
            client.sendMessageToChat(9, "x")
        }
        assertEquals(6, server.requestCount)
    }

    // -------------------------------------------------------------- events

    @Test
    fun `request events have templates, statuses and attempts`(): Unit = runBlocking {
        server.enqueue(json("""{"code":"busy","message":"busy"}""", code = 503))
        server.enqueue(json("""{"members":[]}"""))
        client().use { it.getMembers(42) }
        assertEquals(listOf(503, 200), requests.map { it.status })
        assertEquals(listOf(0, 1), requests.map { it.attempt })
        assertEquals(setOf("GET /chats/{id}/members"), requests.map { "${it.method} ${it.path}" }.toSet())
        assertTrue(requests.all { !it.duration.isNegative && it.error == null })
    }

    @Test
    fun `network errors are events too`(): Unit = runBlocking {
        val port = server.port
        server.close()
        val client = MaxClient(
            MaxClientConfig(TOKEN, baseUrl = "http://127.0.0.1:$port/", retryPolicy = RetryPolicy.NONE, eventListener = listener),
        )
        assertFailsWith<MaxNetworkException> { client.getMyInfo() }
        client.close()
        val event = requests.single()
        assertNull(event.status)
        assertIs<java.io.IOException>(event.error)
        assertEquals("/me", event.path)
    }

    @Test
    fun `failing listeners do not break requests or updates`(): Unit = runBlocking {
        val broken = object : MaxEventListener {
            override fun onRequest(event: RequestEvent) = error("listener")
            override fun onUpdate(event: UpdateEvent) = error("listener")
        }
        server.enqueue(json(SUCCESS))
        val client = client(events = broken)
        assertTrue(client.deleteMessage("m").success)
        val bot = MaxBot(client, BotConfig(eventListener = broken))
        bot.onError { e, _ -> errors += e }
        bot.onMessage { }
        bot.processUpdate(message("x"))
        assertEquals(emptyList(), errors)
        bot.close()
        client.close()
    }

    @Test
    fun `composite listeners`() {
        assertSame(MaxEventListener.NONE, MaxEventListener.composite(emptyList()))
        assertSame(listener, MaxEventListener.composite(listOf(listener)))
        val both = MaxEventListener.composite(listOf(listener, listener))
        val request = RequestEvent("GET", "/me", 200, Duration.ZERO, 0, null)
        both.onRequest(request)
        both.onUpdate(UpdateEvent(message("x"), true, Duration.ZERO, null))
        assertEquals(listOf(request, request), requests)
        assertEquals(2, updates.size)
        MaxEventListener.NONE.onRequest(request)
        MaxEventListener.NONE.onUpdate(updates[0])
    }

    @Test
    fun `update events tell handled, unhandled and failed updates apart`(): Unit = runBlocking {
        val bot = MaxBot(client(), BotConfig(eventListener = listener))
        bot.onError { e, _ -> errors += e }
        bot.onCommand("fail") { error("boom") }
        bot.onCommand("ok") { }
        bot.processUpdate(message("/ok"))
        bot.processUpdate(message("plain"))
        bot.processUpdate(message("/fail"))
        assertEquals(listOf(true, false, true), updates.map { it.handled })
        assertEquals(listOf(null, null, "boom"), updates.map { it.error?.message })
        assertEquals("boom", errors.single().message)
        bot.close()
    }

    // ---------------------------------------------------------- duplicates

    @Test
    fun `duplicates are dropped`(): Unit = runBlocking {
        val bot = MaxBot(client(), BotConfig(deduplicator = InMemoryUpdateDeduplicator(), eventListener = listener))
        val seen = mutableListOf<String?>()
        bot.onMessage { seen += text }
        bot.processUpdate(message("a"))
        bot.processUpdate(message("a"))
        bot.processUpdate(message("b"))
        assertEquals(listOf<String?>("a", "b"), seen)
        assertEquals(listOf(true, false, true), updates.map { it.handled })
        bot.close()
    }

    @Test
    fun `update keys and capacity`() {
        val noBody = MaxJson.parseUpdate(
            """{"update_type":"message_created","timestamp":7,"message":{"recipient":{"chat_id":1},"timestamp":7}}""",
        )
        val callback = MaxJson.parseUpdate(
            """{"update_type":"message_callback","timestamp":1,"callback":{"timestamp":1,"callback_id":"cb","user":{"user_id":3}}}""",
        )
        val started = MaxJson.parseUpdate("""{"update_type":"bot_started","timestamp":9,"chat_id":4,"user":{"user_id":5}}""")
        val unknown = MaxJson.parseUpdate("""{"update_type":"brand_new","timestamp":2}""")
        assertEquals("message_created:mid.x", UpdateDeduplicator.keyOf(message("x")))
        assertEquals("message_created:7", UpdateDeduplicator.keyOf(noBody))
        assertEquals("message_callback:cb", UpdateDeduplicator.keyOf(callback))
        assertEquals("bot_started:9:4:5", UpdateDeduplicator.keyOf(started))
        assertEquals("brand_new:2:null:null", UpdateDeduplicator.keyOf(unknown))

        val dedup = InMemoryUpdateDeduplicator(capacity = 2)
        assertTrue(dedup.isNew(message("1")))
        assertTrue(dedup.isNew(message("2")))
        assertFalse(dedup.isNew(message("1")))
        assertTrue(dedup.isNew(message("3")))
        assertTrue(dedup.isNew(message("1"))) // forgotten: only the last 2 keys are kept
        assertFailsWith<IllegalArgumentException> { InMemoryUpdateDeduplicator(0) }
    }

    // ------------------------------------------------------------ timeouts

    @Test
    fun `slow handlers time out`(): Unit = runBlocking {
        val bot = MaxBot(client(), BotConfig(handlerTimeout = Duration.ofMillis(50)))
        bot.onError { e, _ -> errors += e }
        val interrupted = CompletableDeferred<Boolean>()
        bot.onCommand("kotlin") { delay(10_000) }
        bot.onCommand("java", Handler {
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                interrupted.complete(true)
                throw e
            }
        })
        bot.onCommand("fast") { }
        bot.processUpdate(message("/kotlin"))
        bot.processUpdate(message("/java"))
        bot.processUpdate(message("/fast"))
        assertTrue(withTimeout(5_000) { interrupted.await() })
        assertEquals(2, errors.size)
        val error = assertIs<HandlerTimeoutException>(errors[0])
        assertEquals(Duration.ofMillis(50), error.timeout)
        assertEquals("message_created", error.update.updateType)
        assertTrue(error.message!!.contains("PT0.05S"))
        bot.close()
    }

    @Test
    fun `bot settings are validated and built`() {
        assertFailsWith<IllegalArgumentException> { BotConfig(handlerTimeout = Duration.ZERO) }
        val store = InMemoryMarkerStore()
        val dedup = InMemoryUpdateDeduplicator()
        val config = BotConfig.builder()
            .handlerTimeout(Duration.ofSeconds(5))
            .deduplicator(dedup)
            .markerStore(store)
            .eventListener(listener)
            .build()
        assertEquals(Duration.ofSeconds(5), config.handlerTimeout)
        assertSame(dedup, config.deduplicator)
        assertSame(store, config.markerStore)
        assertSame(listener, config.eventListener)
        val client = MaxClientConfig.builder(TOKEN).rateLimit(RateLimit(5.0)).build()
        assertEquals(5.0, client.rateLimit?.requestsPerSecond)
        assertTrue(client.toString().contains("rateLimit=RateLimit(requestsPerSecond=5.0"))
    }

    // -------------------------------------------------------------- marker

    @Test
    fun `marker stores`(): Unit = runBlocking {
        val memory = InMemoryMarkerStore()
        assertNull(memory.load())
        memory.save(5)
        assertEquals(5, memory.load())

        val dir = Files.createTempDirectory("maxapi")
        val file = FileMarkerStore(dir.resolve("state/marker.txt"))
        assertNull(file.load())
        file.save(42)
        file.save(43)
        assertEquals(43, file.load())
        Files.writeString(dir.resolve("state/marker.txt"), "garbage")
        assertNull(file.load())
        dir.toFile().deleteRecursively()

        var stored: Long? = 1
        val blocking = object : BlockingMarkerStore() {
            override fun loadMarker(): Long? = stored
            override fun saveMarker(marker: Long) {
                stored = marker
            }
        }
        assertEquals(1, blocking.load())
        blocking.save(2)
        assertEquals(2, stored)
    }

    private fun pollingServer(pages: List<String>, markers: MutableList<String?>) = object : Dispatcher() {
        private var polls = 0

        @Synchronized
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.url.encodedPath != "/updates") return json(SUCCESS)
            markers += request.url.queryParameter("marker")
            return json(pages.getOrElse(polls++) { """{"marker":${pages.size + 10},"updates":[]}""" })
        }
    }

    private fun updateJson(text: String) =
        """{"update_type":"message_created","timestamp":1,"message":${messageJson(100, text, "mid.$text")}}"""

    @Test
    fun `polling continues from the saved marker and saves it after handling`(): Unit = runBlocking {
        val sent = CopyOnWriteArrayList<String?>()
        server.dispatcher = pollingServer(
            listOf("""{"marker":20,"updates":[${updateJson("a")},${updateJson("b")}]}"""),
            sent,
        )
        val saved = CopyOnWriteArrayList<Long>()
        val store = object : MarkerStore {
            override suspend fun load(): Long = 10
            override suspend fun save(marker: Long) {
                saved += marker
            }
        }
        val client = client(events = MaxEventListener.NONE)
        val bot = MaxBot(client, BotConfig(markerStore = store, pollTimeoutSeconds = 0, minPollInterval = Duration.ofMillis(5)))
        val handled = CopyOnWriteArrayList<String?>()
        bot.onMessage {
            delay(50) // the marker is saved only after this
            assertTrue(saved.isEmpty())
            handled += text
        }
        bot.start()
        withTimeout(5_000) { while (saved.size < 2) delay(10) }
        bot.stop()
        bot.close()
        client.close()

        assertEquals("10", sent[0])
        assertEquals("20", sent[1])
        assertEquals(listOf<String?>("a", "b"), handled.toList())
        assertEquals(listOf(20L, 11L), saved.take(2))
    }

    @Test
    fun `marker save errors are reported`(): Unit = runBlocking {
        val sent = CopyOnWriteArrayList<String?>()
        server.dispatcher = pollingServer(emptyList(), sent)
        val store = object : MarkerStore {
            override suspend fun load(): Long? = null
            override suspend fun save(marker: Long) = throw java.io.IOException("disk full")
        }
        val client = client(events = MaxEventListener.NONE)
        val bot = MaxBot(client, BotConfig(markerStore = store, pollTimeoutSeconds = 0, minPollInterval = Duration.ofMillis(5)))
        bot.onError { e, _ -> errors += e }
        bot.onMessage { }
        val job = bot.start()
        withTimeout(5_000) { while (errors.isEmpty()) delay(10) }
        bot.stop()
        job.join()
        bot.close()
        client.close()
        assertNull(sent[0])
        assertEquals("disk full", errors.first().message)
    }
}
