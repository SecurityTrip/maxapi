package ru.securitytrip.maxapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.securitytrip.maxapi.bot.BlockingBroadcastStore
import ru.securitytrip.maxapi.bot.BroadcastOutcome
import ru.securitytrip.maxapi.bot.BroadcastStore
import ru.securitytrip.maxapi.bot.BroadcastTarget
import ru.securitytrip.maxapi.bot.Broadcaster
import ru.securitytrip.maxapi.bot.InMemoryBroadcastStore
import ru.securitytrip.maxapi.bot.KnownChat
import ru.securitytrip.maxapi.client.TooManyRequestsException
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.message.NewMessageBody
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BroadcastTest {
    private val api = FakeApi()
    private val store = InMemoryBroadcastStore()

    @AfterTest
    fun tearDown() = api.close()

    private fun broadcaster(rate: Double = 1000.0, maxAttempts: Int = 5) = Broadcaster(api.client, store, rate, parallelism = 4, maxAttempts = maxAttempts)

    private val FakeApi.Call.target: String get() = query["user_id"]?.let { "u:$it" } ?: "c:${query["chat_id"]}"

    @Test
    fun `targets are parsed and printed`() {
        assertEquals(BroadcastTarget(BroadcastTarget.Kind.USER, 1), BroadcastTarget.parse("u:1"))
        assertEquals(BroadcastTarget.chat(-5), BroadcastTarget.parse(BroadcastTarget.chat(-5).key))
        assertEquals("c:3", BroadcastTarget.of(KnownChat(3, ChatType.CHAT)).toString())
        assertEquals(listOf(BroadcastTarget.user(1), BroadcastTarget.user(2)), BroadcastTarget.users(listOf(1L, 2L)))
        assertEquals(listOf(BroadcastTarget.chat(1)), BroadcastTarget.chats(listOf(1L)))
        for (bad in listOf("", "u", "u:", "x:1", "u:abc")) assertFailsWith<IllegalArgumentException> { BroadcastTarget.parse(bad) }
    }

    @Test
    fun `messages go to users and chats and the report counts outcomes`(): Unit = runBlocking {
        api.respond = { call ->
            when (call.target) {
                "u:2" -> FakeApi.Reply(403, """{"code":"chat.denied","message":"bot stopped"}""")
                "c:3" -> FakeApi.Reply(404, """{"code":"not.found","message":"no chat"}""")
                "u:4" -> FakeApi.Reply(400, """{"code":"proto.payload","message":"bad"}""")
                else -> null
            }
        }
        val targets = BroadcastTarget.users(listOf(1L, 2L, 4L)) + BroadcastTarget.chat(3) + BroadcastTarget.chat(5) + BroadcastTarget.user(1)
        val report = broadcaster().send("news", targets) { target -> message { text("Hi $target") } }

        assertEquals("news", report.broadcastId)
        assertEquals(5, report.total)
        assertEquals(0, report.skipped)
        assertEquals(2, report.sent)
        assertEquals(setOf(BroadcastTarget.user(2), BroadcastTarget.chat(3)), report.unavailable.toSet())
        assertEquals(setOf(BroadcastTarget.user(4)), report.failed.keys)
        assertFalse(report.isComplete)
        assertTrue(report.duration >= Duration.ZERO)
        assertEquals(setOf("Hi u:1", "Hi c:5", "Hi u:2", "Hi c:3", "Hi u:4"), api.texts.toSet())
        assertEquals(
            mapOf(
                BroadcastTarget.user(1) to BroadcastOutcome.SENT,
                BroadcastTarget.user(2) to BroadcastOutcome.UNAVAILABLE,
                BroadcastTarget.chat(3) to BroadcastOutcome.UNAVAILABLE,
                BroadcastTarget.user(4) to BroadcastOutcome.FAILED,
                BroadcastTarget.chat(5) to BroadcastOutcome.SENT,
            ),
            store.outcomes("news"),
        )
    }

    @Test
    fun `a broadcast run again skips handled targets and retries failed ones`(): Unit = runBlocking {
        var fail = true
        api.respond = { if (fail && it.target == "u:3") FakeApi.Reply(500, "{}") else if (it.target == "u:2") FakeApi.Reply(403, "{}") else null }
        val targets = BroadcastTarget.users(listOf(1L, 2L, 3L))
        val body = NewMessageBody(text = "Sale")
        assertEquals(setOf(BroadcastTarget.user(3)), broadcaster().send("sale", targets, body).failed.keys)

        fail = false
        api.calls.clear()
        val second = broadcaster().send("sale", targets, body)
        assertEquals(listOf("u:3"), api.sent.map { it.target })
        assertEquals(2, second.skipped)
        assertEquals(1, second.sent)
        assertTrue(second.isComplete)

        store.delete("sale")
        assertEquals(emptyMap(), store.outcomes("sale"))
    }

    @Test
    fun `429 pauses all sending for Retry-After and the message is sent again`(): Unit = runBlocking {
        val rejected = AtomicInteger()
        api.respond = { if (it.target == "u:1" && rejected.getAndIncrement() == 0) FakeApi.Reply(429, "{}", retryAfter = "1") else null }
        val started = System.nanoTime()
        val report = broadcaster().send("limited", BroadcastTarget.users(listOf(1L)), NewMessageBody(text = "x"))
        assertEquals(1, report.sent)
        assertEquals(2, api.sent.size)
        assertTrue(System.nanoTime() - started >= 900_000_000)
    }

    @Test
    fun `429 without Retry-After pauses a second, and a message rejected too often fails`(): Unit = runBlocking {
        api.respond = { FakeApi.Reply(429, "{}") }
        val report = broadcaster(maxAttempts = 2).send("limited", BroadcastTarget.users(listOf(1L)), NewMessageBody(text = "x"))
        assertEquals(2, api.sent.size)
        assertIs<TooManyRequestsException>(report.failed.getValue(BroadcastTarget.user(1)))
        assertEquals(BroadcastOutcome.FAILED, store.outcomes("limited").getValue(BroadcastTarget.user(1)))
    }

    @Test
    fun `messages keep to the rate`(): Unit = runBlocking {
        val started = System.nanoTime()
        broadcaster(rate = 20.0).send("slow", BroadcastTarget.users((1L..6L).toList()), NewMessageBody(text = "x"))
        // A burst of 1: six messages at 20 per second take at least 5 intervals of 50 ms.
        assertTrue(System.nanoTime() - started >= 240_000_000)
        assertEquals(6, api.sent.size)
    }

    @Test
    fun `cancelling keeps the progress`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val sent = CopyOnWriteArrayList<String>()
        api.respond = { call ->
            sent += call.target
            if (call.target == "u:2") runBlocking { gate.await() }
            null
        }
        val job = launch(Dispatchers.Default) {
            Broadcaster(api.client, store, 1000.0, parallelism = 1).send("long", BroadcastTarget.users(listOf(1L, 2L, 3L)), NewMessageBody(text = "x"))
        }
        withTimeout(5_000) { while (sent.size < 2) kotlinx.coroutines.delay(10) }
        val cancelling = async { job.cancelAndJoin() }
        gate.complete(Unit)
        cancelling.await()
        assertEquals(BroadcastOutcome.SENT, store.outcomes("long")[BroadcastTarget.user(1)])
        assertFalse(BroadcastTarget.user(3) in store.outcomes("long"))
    }

    @Test
    fun `errors of the message and the store`(): Unit = runBlocking {
        val report = broadcaster().send("bad", BroadcastTarget.users(listOf(1L))) { error("no text") }
        assertEquals("no text", report.failed.values.single().message)

        val failing = object : BroadcastStore by store {
            override suspend fun record(broadcastId: String, target: BroadcastTarget, outcome: BroadcastOutcome) = error("db down")
        }
        assertFailsWith<IllegalStateException> {
            Broadcaster(api.client, failing).send("x", BroadcastTarget.users(listOf(1L)), NewMessageBody(text = "x"))
        }
    }

    @Test
    fun `settings are checked and blocking versions work`() {
        assertFailsWith<IllegalArgumentException> { Broadcaster(api.client, requestsPerSecond = 0.0) }
        assertFailsWith<IllegalArgumentException> { Broadcaster(api.client, parallelism = 0) }
        assertFailsWith<IllegalArgumentException> { Broadcaster(api.client, maxAttempts = 0) }
        val broadcaster = Broadcaster(api.client)
        assertEquals(25.0, broadcaster.requestsPerSecond)
        assertEquals(8, broadcaster.parallelism)
        assertEquals(5, broadcaster.maxAttempts)
        assertIs<InMemoryBroadcastStore>(broadcaster.store)
        assertEquals(api.client, broadcaster.client)
        assertEquals(1, broadcaster.sendBlocking("a", BroadcastTarget.chats(listOf(1L)), NewMessageBody(text = "x")).sent)
        assertEquals(1, broadcaster.sendBlocking("b", BroadcastTarget.chats(listOf(1L))) { NewMessageBody(text = "x") }.sent)
    }

    @Test
    fun `a blocking store runs on the IO dispatcher`(): Unit = runBlocking {
        val threads = CopyOnWriteArrayList<String>()
        val blocking = object : BlockingBroadcastStore() {
            val map = HashMap<BroadcastTarget, BroadcastOutcome>()
            override fun load(broadcastId: String) = map.toMap().also { threads += Thread.currentThread().name }
            override fun save(broadcastId: String, target: BroadcastTarget, outcome: BroadcastOutcome) { map[target] = outcome }
            override fun remove(broadcastId: String) = map.clear()
        }
        Broadcaster(api.client, blocking).send("x", BroadcastTarget.users(listOf(1L)), NewMessageBody(text = "x"))
        assertEquals(mapOf(BroadcastTarget.user(1) to BroadcastOutcome.SENT), blocking.outcomes("x"))
        blocking.delete("x")
        assertEquals(emptyMap(), blocking.outcomes("x"))
        assertTrue(threads.all { it.contains("DefaultDispatcher") })
    }
}
