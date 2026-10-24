package ru.securitytrip.maxapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.WebhookHandler
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxException
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.model.update.BotAddedToChatUpdate
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateType
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxBotTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient
    private lateinit var bot: MaxBot

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = server.client()
        bot = MaxBot(client, BotConfig(minPollInterval = Duration.ofMillis(10), pollTimeoutSeconds = 0))
    }

    @AfterTest
    fun tearDown() {
        bot.close()
        client.close()
        server.close()
    }

    private fun messageUpdate(text: String, chatId: Long = 100): Update = MaxJson.parseUpdate(
        """{"update_type":"message_created","timestamp":1,"message":${messageJson(chatId = chatId, text = text)}}""",
    )

    private fun callbackUpdate(payload: String): Update = MaxJson.parseUpdate(
        """{"update_type":"message_callback","timestamp":1,
            "callback":{"timestamp":1,"callback_id":"cb-$payload","payload":"$payload","user":{"user_id":3}},
            "message":${messageJson()}}""",
    )

    @Test
    fun `commands are routed before the generic message handler`(): Unit = runBlocking {
        bot.username = "echo_bot"
        val seen = mutableListOf<String>()
        bot.onCommand("/start") { seen += "start:$commandArgs" }
        bot.onCommand("help") { seen += "help" }
        bot.onMessage({ it.text == "ping" }) { seen += "ping" }
        bot.onMessage { seen += "any:$text" }

        bot.processUpdate(messageUpdate("/start deep link"))
        bot.processUpdate(messageUpdate("/Help@echo_bot"))
        bot.processUpdate(messageUpdate("ping"))
        bot.processUpdate(messageUpdate("/unknown"))
        bot.processUpdate(messageUpdate("hello"))
        bot.processUpdate(callbackUpdate("ignored"))

        assertEquals(listOf("start:deep link", "help", "ping", "any:/unknown", "any:hello"), seen)
    }

    @Test
    fun `callback handlers match payload and answer the callback`(): Unit = runBlocking {
        server.enqueue(json(SUCCESS))
        val seen = mutableListOf<String>()
        bot.onCallback("like") {
            seen += "like by ${user.userId}"
            answer("Thanks")
        }
        bot.onCallback { seen += "other:$payload" }

        bot.processUpdate(callbackUpdate("like"))
        bot.processUpdate(callbackUpdate("dislike"))

        assertEquals(listOf("like by 3", "other:dislike"), seen)
        val request = server.take()
        assertEquals("/answers", request.url.encodedPath)
        assertEquals("cb-like", request.url.queryParameter("callback_id"))
        assertEquals("""{"notification":"Thanks"}""", request.bodyText)
    }

    @Test
    fun `reply sends to the chat of the update`(): Unit = runBlocking {
        server.enqueue(json("""{"message":${messageJson(chatId = 77)}}"""))
        bot.onMessage { replyToMessage("pong") }

        bot.processUpdate(messageUpdate("ping", chatId = 77))

        val request = server.take()
        assertEquals("77", request.url.queryParameter("chat_id"))
        assertEquals("""{"text":"pong","link":{"type":"reply","mid":"mid.1"}}""", request.bodyText)
    }

    @Test
    fun `handler errors go to onError and do not stop dispatching`(): Unit = runBlocking {
        val errors = mutableListOf<Pair<String?, String?>>()
        bot.onCommand("fail") { error("boom") }
        bot.onMessage { }
        bot.onError { error, update -> errors += error.message to update?.updateType }

        bot.processUpdate(messageUpdate("/fail"))
        bot.processUpdate(messageUpdate("ok"))

        assertEquals(listOf<Pair<String?, String?>>("boom" to "message_created"), errors)
    }

    @Test
    fun `generic update handlers and subscribed types`(): Unit = runBlocking {
        bot.onCommand("start") { }
        bot.onCallback { }
        assertEquals(setOf(UpdateType.MESSAGE_CREATED, UpdateType.MESSAGE_CALLBACK), bot.subscribedUpdateTypes())

        val added = CompletableDeferred<Long?>()
        bot.on<BotAddedToChatUpdate> { added.complete(chatId) }
        bot.processUpdate(
            MaxJson.parseUpdate("""{"update_type":"bot_added","timestamp":1,"chat_id":-5,"user":{"user_id":1},"is_channel":false}"""),
        )
        assertEquals(-5L, added.await())

        bot.onUpdate(Update::class.java) { }
        assertNull(bot.subscribedUpdateTypes())
    }

    @Test
    fun `long polling passes marker and update types and dispatches updates`(): Unit = runBlocking {
        val updateRequests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath != "/updates") return json(SUCCESS)
                updateRequests += request
                return if (updateRequests.size == 1) {
                    json(
                        """{"marker":42,"updates":[
                            {"update_type":"message_created","timestamp":1,"message":${messageJson(chatId = 1, text = "a")}},
                            {"update_type":"message_created","timestamp":2,"message":${messageJson(chatId = 1, text = "b")}},
                            {"update_type":"message_created","timestamp":3,"message":${messageJson(chatId = 2, text = "c")}}
                        ]}""",
                    )
                } else {
                    json("""{"updates":[],"marker":42}""")
                }
            }
        }
        val received = Collections.synchronizedList(mutableListOf<String>())
        val done = CompletableDeferred<Unit>()
        bot.onMessage {
            received += text!!
            if (received.size == 3) done.complete(Unit)
        }

        val job = bot.start()
        withTimeout(5_000) { done.await() }
        while (updateRequests.size < 2) Thread.sleep(10)
        bot.stop()
        job.join()

        // Updates of one chat keep their order.
        assertTrue(received.indexOf("a") < received.indexOf("b"))
        assertEquals(setOf("a", "b", "c"), received.toSet())
        assertNull(updateRequests[0].url.queryParameter("marker"))
        assertEquals("42", updateRequests[1].url.queryParameter("marker"))
        assertEquals("message_created", updateRequests[0].url.queryParameter("types"))
        assertEquals(TOKEN, updateRequests[0].headers["Authorization"])
    }

    @Test
    fun `polling stops with a clear error when webhook is active`() {
        server.enqueue(json("""{"code":"method.not.allowed","message":"Webhook subscription is active"}""", code = 405))
        bot.onMessage { }
        val error = assertFailsWith<MaxException> { bot.startBlocking() }
        assertTrue(error.message!!.contains("unsubscribe"))
    }

    @Test
    fun `webhook handler checks the secret`(): Unit = runBlocking {
        val seen = mutableListOf<String?>()
        bot.onMessage { seen += text }
        val handler = bot.webhookHandler("my-secret")
        val body = """{"update_type":"message_created","timestamp":1,"message":${messageJson(text = "via hook")}}"""

        assertFalse(handler.handle(body, null))
        assertFalse(handler.handle(body, "wrong"))
        assertTrue(handler.handle(body, "my-secret"))
        assertTrue(bot.webhookHandler().handleBlocking(body.toByteArray(), null))

        assertEquals(listOf<String?>("via hook", "via hook"), seen)
        assertFailsWith<MaxException> { handler.handle("not json", "my-secret") }
        assertEquals("X-Max-Bot-Api-Secret", WebhookHandler.SECRET_HEADER)
    }

    @Test
    fun `setWebhook subscribes with registered update types`(): Unit = runBlocking {
        server.enqueue(json(SUCCESS))
        bot.onBotStarted { }

        bot.setWebhook("https://bot.example/hook", "secret1")

        val request = server.take()
        assertEquals("/subscriptions", request.url.encodedPath)
        assertEquals(
            """{"url":"https://bot.example/hook","secret":"secret1","update_types":["bot_started"]}""",
            request.bodyText,
        )
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `bot started context exposes deep link payload`(): Unit = runBlocking {
        val payload = CompletableDeferred<String?>()
        bot.onBotStarted { payload.complete(this.payload) }
        val update = MaxJson.parseUpdate(
            """{"update_type":"bot_started","timestamp":1,"chat_id":1,"user":{"user_id":2},"payload":"ref-42"}""",
        )
        assertIs<ru.securitytrip.maxapi.model.update.BotStartedUpdate>(update)

        bot.processUpdate(update)

        assertEquals("ref-42", payload.await())
    }
}
