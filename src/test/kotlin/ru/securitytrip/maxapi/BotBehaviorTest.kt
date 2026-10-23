package ru.securitytrip.maxapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.ErrorHandler
import ru.securitytrip.maxapi.bot.Handler
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.MessageContext
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.UnauthorizedException
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.update.BotStartedUpdate
import ru.securitytrip.maxapi.model.update.Update
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

class BotBehaviorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient
    private lateinit var bot: MaxBot
    private val errors = CopyOnWriteArrayList<Pair<Throwable, Update?>>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = server.client()
        bot = MaxBot(
            client,
            BotConfig(minPollInterval = Duration.ofMillis(5), pollTimeoutSeconds = 0, maxErrorBackoff = Duration.ofMillis(20)),
        )
        bot.onError { error, update -> errors += error to update }
    }

    @AfterTest
    fun tearDown() {
        bot.close()
        client.close()
        server.close()
    }

    private fun update(json: String): Update = MaxJson.parseUpdate(json)

    private fun messageUpdate(text: String?, chatId: Long = 100) = update(
        """{"update_type":"message_created","timestamp":1,"message":{"sender":{"user_id":1},
            "recipient":{"chat_id":$chatId,"chat_type":"dialog"},"timestamp":1,
            "body":{"mid":"mid.$chatId","seq":1${text?.let { ""","text":"$it"""" }.orEmpty()}}}}""",
    )

    /** A callback whose message was deleted: the chat is unknown, the user is known. */
    private val orphanCallback = update(
        """{"update_type":"message_callback","timestamp":1,
            "callback":{"timestamp":1,"callback_id":"cb1","payload":"p","user":{"user_id":3}}}""",
    )

    /** An update with neither chat nor user. */
    private val anonymous = update("""{"update_type":"unheard_of","timestamp":1}""")

    // ------------------------------------------------------------ contexts

    @Test
    fun `message context exposes message details`(): Unit = runBlocking {
        val seen = CompletableDeferred<MessageContext>()
        bot.onMessage { seen.complete(this) }

        bot.processUpdate(messageUpdate("hello"))

        val ctx = seen.await()
        assertEquals("hello", ctx.text)
        assertEquals("mid.100", ctx.messageId)
        assertEquals(100L, ctx.chatId)
        assertEquals(1L, ctx.user?.userId)
        assertNull(ctx.command)
        assertEquals("", ctx.commandArgs)
        assertTrue(ctx.toString().startsWith("MessageContext("))
        assertEquals(client, ctx.client)
        assertEquals(bot, ctx.bot)
    }

    @Test
    fun `command parsing`() {
        val cases = mapOf(
            "/start" to MessageContext.ParsedCommand("start", null, ""),
            "  /Start  deep link  " to MessageContext.ParsedCommand("start", null, "deep link"),
            "/start@my_bot arg" to MessageContext.ParsedCommand("start", "my_bot", "arg"),
            "/start@ arg" to MessageContext.ParsedCommand("start", null, "arg"),
            "/START\targ" to MessageContext.ParsedCommand("start", null, "arg"),
            "/a" to MessageContext.ParsedCommand("a", null, ""),
        )
        for ((text, expected) in cases) assertEquals(expected, MessageContext.parseCommand(text), text)
        for (text in listOf(null, "", "/", "/ start", "/@bot", "start", "text /start")) {
            assertNull(MessageContext.parseCommand(text), text)
        }
    }

    @Test
    fun `messages without text are not commands`(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        bot.onCommand("start") { seen += "command" }
        bot.onMessage { seen += "message:${text}" }

        bot.processUpdate(messageUpdate(null))

        assertEquals(listOf("message:null"), seen)
    }

    @Test
    fun `blank command names are rejected`() {
        assertFailsWith<IllegalArgumentException> { bot.onCommand("/") { } }
        assertFailsWith<IllegalArgumentException> { bot.onCommand(" ") { } }
    }

    @Test
    fun `reply falls back to the user when the chat is unknown`(): Unit = runBlocking {
        server.enqueue(json("""{"message":${messageJson()}}"""))
        bot.onCallback { reply("to user") }

        bot.processUpdate(orphanCallback)

        val request = server.take()
        assertEquals("3", request.url.queryParameter("user_id"))
        assertNull(request.url.queryParameter("chat_id"))
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `reply without chat and user is reported as an error`(): Unit = runBlocking {
        bot.onUpdate(Update::class.java) { reply("nowhere") }

        bot.processUpdate(anonymous)

        val (error, update) = errors.single()
        assertIs<IllegalStateException>(error)
        assertEquals(anonymous, update)
    }

    @Test
    fun `typing and sendAction go to the chat`(): Unit = runBlocking {
        server.enqueue(json(SUCCESS))
        server.enqueue(json(SUCCESS))
        bot.onMessage {
            typing()
            sendAction(SenderAction.SENDING_PHOTO)
        }

        bot.processUpdate(messageUpdate("x", chatId = 55))

        assertEquals("""{"action":"typing_on"}""", server.take().bodyText)
        val second = server.take()
        assertEquals("/chats/55/actions", second.url.encodedPath)
        assertEquals("""{"action":"sending_photo"}""", second.bodyText)
    }

    @Test
    fun `sendAction without a chat is reported as an error`(): Unit = runBlocking {
        bot.onCallback { typing() }

        bot.processUpdate(orphanCallback)

        assertIs<IllegalArgumentException>(errors.single().first)
    }

    @Test
    fun `reply DSL and replyToMessage with a body`(): Unit = runBlocking {
        server.enqueue(json("""{"message":${messageJson()}}"""))
        server.enqueue(json("""{"message":${messageJson()}}"""))
        bot.onMessage {
            reply { text("menu"); keyboard { row { callback("A", "a") } } }
            replyToMessage(message { text("quoted") })
        }

        bot.processUpdate(messageUpdate("x"))

        assertTrue(server.take().bodyText.contains("inline_keyboard"))
        assertEquals("""{"text":"quoted","link":{"type":"reply","mid":"mid.100"}}""", server.take().bodyText)
    }

    @Test
    fun `callback context answers and edits`(): Unit = runBlocking {
        repeat(3) { server.enqueue(json(SUCCESS)) }
        bot.onCallback {
            assertEquals("p", payload)
            assertNull(message)
            assertEquals("cb1", callback.callbackId)
            answer("note")
            answer(CallbackAnswer(notification = "again"))
            editMessage(NewMessageBody(text = "edited"), notification = "done")
        }

        bot.processUpdate(orphanCallback)

        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals("""{"notification":"note"}""", server.take().bodyText)
        assertEquals("""{"notification":"again"}""", server.take().bodyText)
        assertEquals("""{"message":{"text":"edited"},"notification":"done"}""", server.take().bodyText)
    }

    @Test
    fun `bot started context`(): Unit = runBlocking {
        val seen = CompletableDeferred<Triple<Long, Long, String?>>()
        bot.onBotStarted { seen.complete(Triple(chatId, user.userId, payload)) }

        bot.processUpdate(update("""{"update_type":"bot_started","timestamp":1,"chat_id":9,"user":{"user_id":4}}"""))

        assertEquals(Triple(9L, 4L, null), seen.await())
    }

    // ------------------------------------------------------------- routing

    @Test
    fun `first matching handler wins across handler kinds`(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        bot.on<BotStartedUpdate> { seen += "generic" }
        bot.onBotStarted { seen += "specific" }

        bot.processUpdate(update("""{"update_type":"bot_started","timestamp":1,"chat_id":9,"user":{"user_id":4}}"""))

        assertEquals(listOf("generic"), seen)
    }

    @Test
    fun `filter errors are reported and later updates still work`(): Unit = runBlocking {
        val seen = mutableListOf<String?>()
        bot.onMessage({ error("bad filter") }) { seen += "never" }

        bot.processUpdate(messageUpdate("x"))

        assertEquals("bad filter", errors.single().first.message)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a failing error handler does not break dispatching`(): Unit = runBlocking {
        val seen = mutableListOf<String?>()
        bot.onError { _, _ -> error("handler of handlers failed") }
        bot.onCommand("fail") { error("boom") }
        bot.onMessage { seen += text }

        bot.processUpdate(messageUpdate("/fail"))
        bot.processUpdate(messageUpdate("ok"))

        assertEquals(listOf<String?>("ok"), seen)
    }

    @Test
    fun `java handlers and error handlers`(): Unit = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val javaErrors = CopyOnWriteArrayList<Throwable>()
        bot.onError(ErrorHandler { error, _ -> javaErrors += error })
        bot.onCommand("checked", Handler<MessageContext> { throw java.io.IOException("checked failure") })
        bot.onMessage({ ctx: MessageContext -> ctx.text == "filtered" }, Handler { seen += "filtered" })
        bot.onCallback("p", Handler { seen += "callback ${it.payload}" })
        bot.onCallback({ ctx: ru.securitytrip.maxapi.bot.CallbackContext -> false }, Handler { seen += "never" })
        bot.onCallback(Handler { seen += "any callback" })
        bot.onBotStarted(Handler { seen += "started ${it.user.userId}" })
        bot.onUpdate(Update::class.java, Handler { seen += "other ${it.update.updateType}" })
        bot.onMessage(Handler { seen += "message" })

        bot.processUpdate(messageUpdate("/checked"))
        bot.processUpdate(messageUpdate("filtered"))
        bot.processUpdate(orphanCallback)
        bot.processUpdate(update("""{"update_type":"bot_started","timestamp":1,"chat_id":9,"user":{"user_id":4}}"""))
        bot.processUpdate(anonymous)
        bot.processUpdateBlocking(messageUpdate("plain"))

        assertEquals("checked failure", javaErrors.single().message)
        assertEquals(listOf("filtered", "callback p", "started 4", "other unheard_of", "other message_created"), seen)
    }

    // ------------------------------------------------------------- polling

    private fun dispatcher(updates: (Int) -> MockResponse) = object : Dispatcher() {
        private var polls = 0

        @Synchronized
        override fun dispatch(request: RecordedRequest): MockResponse =
            if (request.url.encodedPath == "/updates") updates(++polls) else json("""{"message":${messageJson()}}""")
    }

    private fun page(vararg updates: String, marker: Long = 1) =
        json("""{"marker":$marker,"updates":[${updates.joinToString(",")}]}""")

    private fun messageJsonUpdate(chatId: Long, text: String) =
        """{"update_type":"message_created","timestamp":1,"message":${messageJson(chatId = chatId, text = text)}}"""

    @Test
    fun `transient polling errors are reported and polling continues`(): Unit = runBlocking {
        server.dispatcher = dispatcher { poll ->
            when (poll) {
                1 -> json("""{"code":"internal","message":"oops"}""", code = 500)
                2 -> page(messageJsonUpdate(1, "after error"))
                else -> page()
            }
        }
        val done = CompletableDeferred<String?>()
        bot.onMessage { done.complete(text) }

        val job = bot.start()
        assertEquals("after error", withTimeout(5_000) { done.await() })
        bot.stop()
        job.join()

        val (error, update) = errors.first()
        assertIs<MaxApiException>(error)
        assertEquals(500, error.httpStatus)
        assertNull(update)
    }

    @Test
    fun `network errors during polling are reported and polling continues`(): Unit = runBlocking {
        server.dispatcher = dispatcher { poll ->
            when (poll) {
                1 -> page(messageJsonUpdate(1, "lost")).newBuilder().onResponseBody(mockwebserver3.SocketEffect.CloseSocket()).build()
                2 -> page(messageJsonUpdate(1, "recovered"))
                else -> page()
            }
        }
        // Without retries in the client, the error reaches the polling loop.
        val noRetries = MaxClient(
            ru.securitytrip.maxapi.client.MaxClientConfig(
                TOKEN,
                baseUrl = server.url("/").toString(),
                retryPolicy = ru.securitytrip.maxapi.client.RetryPolicy.NONE,
                httpClient = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            ),
        )
        val pollingBot = MaxBot(noRetries, BotConfig(minPollInterval = Duration.ofMillis(5), pollTimeoutSeconds = 0, maxErrorBackoff = Duration.ofMillis(20)))
        val pollingErrors = CopyOnWriteArrayList<Throwable>()
        val done = CompletableDeferred<String?>()
        pollingBot.onError { error, _ -> pollingErrors += error }
        pollingBot.onMessage { done.complete(text) }

        pollingBot.use {
            val job = it.start()
            assertEquals("recovered", withTimeout(5_000) { done.await() })
            it.stop()
            job.join()
        }
        noRetries.close()

        assertIs<ru.securitytrip.maxapi.client.MaxNetworkException>(pollingErrors.first())
    }

    @Test
    fun `invalid token stops polling with an exception`() {
        server.enqueue(json("""{"code":"verify.token","message":"Invalid access_token"}""", code = 401))
        bot.onMessage { }

        assertFailsWith<UnauthorizedException> { bot.startBlocking() }
        assertIs<UnauthorizedException>(errors.single().first)
    }

    @Test
    fun `start twice is rejected and polling can be restarted after stop`(): Unit = runBlocking {
        server.dispatcher = dispatcher { page() }
        bot.onMessage { }

        val first = bot.start()
        assertFailsWith<IllegalStateException> { bot.start() }
        bot.stop()
        first.join()

        val second = bot.start()
        assertTrue(second.isActive)
        bot.stop()
        second.join()
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `startPolling stops when its coroutine is cancelled`(): Unit = runBlocking {
        server.dispatcher = dispatcher { page() }
        bot.onMessage { }

        val job = launch { bot.startPolling() }
        delay(100)
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `different chats are handled in parallel, one chat in order`(): Unit = runBlocking {
        server.dispatcher = dispatcher { poll ->
            if (poll == 1) {
                page(messageJsonUpdate(1, "a1"), messageJsonUpdate(2, "b1"), messageJsonUpdate(1, "a2"))
            } else {
                page()
            }
        }
        val gate = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val done = CompletableDeferred<Unit>()
        bot.onMessage {
            events += "start $text"
            if (text == "a1") gate.await() // chat 1 is stuck until chat 2 is handled
            if (text == "b1") gate.complete(Unit)
            events += "end $text"
            if (text == "a2") done.complete(Unit)
        }

        val job = bot.start()
        withTimeout(5_000) { done.await() }
        bot.stop()
        job.join()

        // a1 can only finish after b1 opened the gate, so chat 2 was handled while chat 1 was busy.
        // (Had the chats been handled sequentially, a1 would have waited forever and the test timed out.)
        assertTrue(events.indexOf("start b1") < events.indexOf("end a1"), events.toString())
        // Within chat 1, a2 starts only after a1 is done.
        assertTrue(events.indexOf("end a1") < events.indexOf("start a2"), events.toString())
    }

    @Test
    fun `polling requests only handled update types`(): Unit = runBlocking {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return page()
            }
        }
        bot.onBotStarted { }
        bot.onCallback { }

        val job = bot.start()
        withTimeout(5_000) { while (requests.isEmpty()) delay(5) }
        bot.stop()
        job.join()

        val query = requests.first().url
        assertEquals("bot_started,message_callback", query.queryParameter("types"))
        assertEquals("100", query.queryParameter("limit"))
        assertEquals("0", query.queryParameter("timeout"))
    }

    // ------------------------------------------------------------- webhook

    @Test
    fun `webhook handleAsync dispatches in the background`(): Unit = runBlocking {
        val seen = CompletableDeferred<String?>()
        bot.onMessage { seen.complete(text) }
        val handler = bot.webhookHandler("secret")
        val body = messageJsonUpdate(1, "async")

        assertFalse(handler.handleAsync(body, "wrong"))
        assertFalse(handler.handleAsync(body.toByteArray(), null))
        assertTrue(handler.handleAsync(body, "secret"))

        assertEquals("async", withTimeout(5_000) { seen.await() })
        assertTrue(handler.handleAsync(body.toByteArray(), "secret"))
    }

    @Test
    fun `webhook handlers without a secret accept everything`(): Unit = runBlocking {
        val seen = CopyOnWriteArrayList<String?>()
        bot.onMessage { seen += text }
        val handler = bot.webhookHandler()

        assertTrue(handler.verifySecret(null))
        assertTrue(handler.verifySecret("anything"))
        assertTrue(handler.handleBlocking(messageJsonUpdate(1, "a"), null))
        assertTrue(handler.handle(messageJsonUpdate(1, "b").toByteArray(), "x"))

        assertEquals(listOf<String?>("a", "b"), seen)
    }

    @Test
    fun `webhook secret check is exact`() {
        val handler = bot.webhookHandler("secret")
        assertTrue(handler.verifySecret("secret"))
        assertFalse(handler.verifySecret("secret "))
        assertFalse(handler.verifySecret("Secret"))
        assertFalse(handler.verifySecret(""))
    }

    @Test
    fun `setWebhookBlocking subscribes with all types when a handler takes everything`() {
        server.enqueue(json(SUCCESS))
        bot.onUpdate(Update::class.java) { }

        assertTrue(bot.setWebhookBlocking("https://bot.example/hook").success)

        assertEquals("""{"url":"https://bot.example/hook"}""", server.take().bodyText)
    }

    // ----------------------------------------------------------- lifecycle

    @Test
    fun `closing a bot keeps a shared client open`() {
        server.enqueue(json("""{"user_id":1}"""))
        val shared = MaxBot(client)
        shared.close()

        assertEquals(1L, client.blocking().getMyInfo().userId)
    }

    @Test
    fun `closing a bot closes the client it owns`() {
        val owning = MaxBot("token")
        owning.close()

        val future = owning.client.async().getMyInfo()
        runBlocking { withTimeout(2_000) { while (!future.isDone) delay(10) } }
        assertTrue(future.isCancelled)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
}
