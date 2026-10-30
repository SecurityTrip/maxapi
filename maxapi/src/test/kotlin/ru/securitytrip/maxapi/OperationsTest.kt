package ru.securitytrip.maxapi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.InMemoryUpdateDeduplicator
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.SuspendUpdateDeduplicator
import ru.securitytrip.maxapi.bot.UpdateDeduplicator
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.UnauthorizedException
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.events.RequestEvent
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.model.update.Update
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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

/** The coroutine context of updates, non-blocking deduplication and the state of polling. */
class OperationsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = server.client()
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.close()
    }

    private fun message(text: String): Update = MaxJson.parseUpdate(
        """{"update_type":"message_created","timestamp":1,"message":${messageJson(text = text, mid = "mid.$text")}}""",
    )

    /** An element that tells which update it was created for. */
    private class Tag(val text: String?) : AbstractCoroutineContextElement(Tag) {
        companion object Key : CoroutineContext.Key<Tag>
    }

    private fun textOf(update: Update): String? = (update as? ru.securitytrip.maxapi.model.update.MessageCreatedUpdate)?.message?.body?.text

    // ------------------------------------------------------- update context

    @Test
    fun `handlers, requests and update events run in the context of the update`(): Unit = runBlocking {
        val seen = CopyOnWriteArrayList<String?>()
        val listener = object : MaxEventListener {
            override fun updateContext(update: Update): CoroutineContext = Tag(textOf(update)) + CoroutineName("update")

            override fun onUpdate(event: UpdateEvent) {
                // Called in the context of the update, so a tracer can end the span it started.
                seen += "event:" + textOf(event.update)
            }
        }
        server.enqueue(json(SUCCESS))
        val bot = MaxBot(client, BotConfig(eventListener = listener))
        bot.onMessage {
            seen += "handler:" + currentCoroutineContext()[Tag]?.text
            client.deleteMessage("m")
        }
        bot.processUpdate(message("a"))

        assertEquals(listOf<String?>("handler:a", "event:a"), seen.toList())
        assertNull(currentCoroutineContext()[Tag], "the context ends with the update")
        bot.close()
    }

    @Test
    fun `contexts of composite listeners are combined and failing ones are skipped`() {
        val first = object : MaxEventListener {
            override fun updateContext(update: Update): CoroutineContext = Tag("first") + CoroutineName("first")
        }
        val second = object : MaxEventListener {
            override fun updateContext(update: Update): CoroutineContext = CoroutineName("second")
        }
        val broken = object : MaxEventListener {
            override fun updateContext(update: Update): CoroutineContext = error("listener")
        }
        val context = MaxEventListener.composite(listOf(first, broken, second)).updateContext(message("x"))
        assertEquals("first", context[Tag]?.text)
        assertEquals("second", context[CoroutineName]?.name)
        assertSame(EmptyCoroutineContext, MaxEventListener.NONE.updateContext(message("x")))
    }

    @Test
    fun `a failing context does not stop the update`(): Unit = runBlocking {
        val updates = CopyOnWriteArrayList<UpdateEvent>()
        val broken = object : MaxEventListener {
            override fun updateContext(update: Update): CoroutineContext = error("listener")
            override fun onUpdate(event: UpdateEvent) {
                updates += event
            }
        }
        val bot = MaxBot(client, BotConfig(eventListener = broken))
        bot.onMessage { }
        bot.processUpdate(message("a"))
        assertTrue(updates.single().handled)
        bot.close()
    }

    // -------------------------------------------------------- deduplication

    @Test
    fun `a suspending deduplicator drops duplicates`(): Unit = runBlocking {
        val seen = mutableSetOf<String>()
        val deduplicator = SuspendUpdateDeduplicator { update -> seen.add(UpdateDeduplicator.keyOf(update)) }
        val bot = MaxBot(client, BotConfig(suspendDeduplicator = deduplicator))
        val handled = mutableListOf<String?>()
        bot.onMessage { handled += text }
        bot.processUpdate(message("a"))
        bot.processUpdate(message("a"))
        bot.processUpdate(message("b"))
        assertEquals(listOf<String?>("a", "b"), handled)
        bot.close()
    }

    /** Implements both interfaces, like the Redis and JDBC deduplicators. */
    private class BothWays : UpdateDeduplicator, SuspendUpdateDeduplicator {
        val calls = mutableListOf<String>()
        private val memory = InMemoryUpdateDeduplicator()

        override fun isNew(update: Update): Boolean {
            calls += "blocking"
            return memory.isNew(update)
        }

        override suspend fun markSeen(update: Update): Boolean {
            calls += "suspending"
            return memory.isNew(update)
        }
    }

    @Test
    fun `the bot calls the suspending method of a deduplicator that has both`(): Unit = runBlocking {
        val deduplicator = BothWays()
        val bot = MaxBot(client, BotConfig(deduplicator = deduplicator))
        var handled = 0
        bot.onMessage { handled++ }
        bot.processUpdate(message("a"))
        bot.processUpdate(message("a"))
        assertEquals(1, handled)
        assertEquals(listOf("suspending", "suspending"), deduplicator.calls)
        bot.close()
    }

    @Test
    fun `only one deduplicator may be set`() {
        val blocking = InMemoryUpdateDeduplicator()
        val suspending = SuspendUpdateDeduplicator { true }
        assertFailsWith<IllegalArgumentException> { BotConfig(deduplicator = blocking, suspendDeduplicator = suspending) }
        val config = BotConfig.builder().suspendDeduplicator(suspending).build()
        assertSame(suspending, config.suspendDeduplicator)
        assertNull(config.deduplicator)
        assertNull(BotConfig().suspendDeduplicator)
    }

    @Test
    fun `the constructor of 1_6 is kept`() {
        val constructor = BotConfig::class.java.constructors.single { it.parameterCount == 12 }
        val chats = ru.securitytrip.maxapi.bot.InMemoryChatStore()
        val config = constructor.newInstance(
            4, 64, 30, 100, Duration.ofMillis(300), Duration.ofSeconds(30),
            ru.securitytrip.maxapi.bot.InMemoryStateStorage(), null, null, null, MaxEventListener.NONE, chats,
        ) as BotConfig
        assertSame(chats, config.chatStore)
        assertNull(config.suspendDeduplicator)
    }

    // ------------------------------------------------------- polling state

    @Test
    fun `polling state tells running, stopped and failed polling apart`(): Unit = runBlocking {
        val bot = MaxBot(client, BotConfig(minPollInterval = Duration.ofMillis(10), pollTimeoutSeconds = 0))
        bot.onError { _, _ -> }
        bot.onMessage { }
        assertFalse(bot.isPolling)
        assertNull(bot.pollingFailure)

        server.enqueue(json("""{"subscriptions":[]}"""))
        repeat(50) { server.enqueue(json("""{"updates":[],"marker":1}""")) }
        val job = bot.start()
        assertTrue(bot.isPolling)
        bot.stop()
        job.join()
        assertFalse(bot.isPolling)
        assertNull(bot.pollingFailure)

        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest) =
                json("""{"code":"verify.token","message":"Invalid access_token"}""", code = 401)
        }
        bot.start().join()
        assertFalse(bot.isPolling)
        assertIs<UnauthorizedException>(bot.pollingFailure)
        bot.close()
    }

    @Test
    fun `listener events without overrides do nothing`() {
        val listener = object : MaxEventListener {}
        listener.onRequest(RequestEvent("GET", "/me", 200, Duration.ZERO, 0, null))
        assertSame(EmptyCoroutineContext, listener.updateContext(message("x")))
    }
}
