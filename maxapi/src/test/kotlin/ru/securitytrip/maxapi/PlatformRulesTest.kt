package ru.securitytrip.maxapi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.client.CallbackChats
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxException
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.MaxLimits
import ru.securitytrip.maxapi.client.RateLimit
import ru.securitytrip.maxapi.client.RateLimiter
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.events.RequestEvent
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.events.WebhookResubscribeEvent
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest
import ru.securitytrip.maxapi.model.attachment.LocationAttachmentRequest
import ru.securitytrip.maxapi.model.keyboard.Button
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.keyboard.ClipboardButton
import ru.securitytrip.maxapi.model.keyboard.LinkButton
import ru.securitytrip.maxapi.model.keyboard.MessageButton
import ru.securitytrip.maxapi.model.keyboard.OpenAppButton
import ru.securitytrip.maxapi.model.keyboard.RequestContactButton
import ru.securitytrip.maxapi.model.keyboard.RequestGeoLocationButton
import ru.securitytrip.maxapi.model.keyboard.UnknownButton
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.NewCommentBody
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.user.BotCommand
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The rules of the Max platform: limits checked before sending, rate limits, long text, webhook upkeep. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformRulesTest {
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

    private fun body(text: String? = null, vararg rows: List<Button>) =
        NewMessageBody(text = text, attachments = if (rows.isEmpty()) null else listOf(InlineKeyboardAttachmentRequest(rows.toList())))

    private fun rejected(body: NewMessageBody): String =
        assertFailsWith<IllegalArgumentException> { runBlocking { client.sendMessageToChat(1, body) } }.message!!

    private fun cb(text: String = "b") = CallbackButton(text, "p")

    private fun callbackUpdate(callbackId: String, chatId: Long?): Update = MaxJson.parseUpdate(
        """{"update_type":"message_callback","timestamp":1,
            "callback":{"timestamp":1,"callback_id":"$callbackId","payload":"p","user":{"user_id":3}}
            ${chatId?.let { ""","message":${messageJson(chatId)}""" }.orEmpty()}}""",
    )

    // ------------------------------------------------------------- addMembers

    @Test
    fun `addMembers is deprecated everywhere`() {
        for (facade in listOf(MaxClient::class.java, ru.securitytrip.maxapi.java.MaxBlockingClient::class.java, ru.securitytrip.maxapi.java.MaxAsyncClient::class.java)) {
            val method = facade.methods.single { it.name == "addMembers" }
            assertContains(method.getAnnotation(Deprecated::class.java).message, "2026-09-30")
        }
    }

    // ------------------------------------------------------------ text limits

    @Test
    fun `text is counted in UTF-16 code units like the server counts it`(): Unit = runBlocking {
        repeat(4) { server.enqueue(json("""{"message":${messageJson()}}""")) }
        client.sendMessageToChat(1, "a".repeat(4000))
        client.sendMessageToChat(1, "😀".repeat(2000))
        client.sendMessageToChat(1, NewMessageBody(text = "", attachments = listOf(LocationAttachmentRequest(1.0, 2.0))))
        client.sendMessageToChat(1, body(null, listOf(cb())))
        assertEquals(4, server.requestCount)
        assertContains(rejected(body("a".repeat(4001))), "Message text is 4001 characters long")
        assertContains(rejected(body("😀".repeat(2000) + "a")), "4001 characters")
        assertContains(rejected(body("😀".repeat(2000) + "a")), "sendLongMessage")
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `edits, answers and comments are checked too`(): Unit = runBlocking {
        val long = "a".repeat(4001)
        assertFailsWith<IllegalArgumentException> { client.editMessage("mid.1", NewMessageBody(text = long)) }
        assertFailsWith<IllegalArgumentException> { client.answerOnCallback("cb", CallbackAnswer(message = NewMessageBody(text = long))) }
        assertFailsWith<IllegalArgumentException> { client.sendComment("mid.1", NewCommentBody(text = long)) }
        val comment = assertFailsWith<IllegalArgumentException> { client.editComment("mid.1", "c", NewCommentBody(text = long)) }
        assertContains(comment.message!!, "Comment text is 4001")
        assertEquals(0, server.requestCount)

        repeat(4) { server.enqueue(json(SUCCESS)) }
        server.enqueue(json("""{"message":${messageJson()}}"""))
        client.editMessage("mid.1", NewMessageBody(text = "a".repeat(4000)))
        client.answerOnCallback("cb", CallbackAnswer(message = NewMessageBody(text = "a".repeat(4000))))
        client.answerOnCallback("cb", CallbackAnswer(notification = "ok"))
        client.editComment("mid.1", "c", NewCommentBody(text = "a".repeat(4000)))
        client.sendComment("mid.1", NewCommentBody())
        assertEquals(5, server.requestCount)
    }

    // -------------------------------------------------------- keyboard limits

    @Test
    fun `keyboards keep to the size limits`() {
        assertContains(rejected(NewMessageBody(attachments = listOf(InlineKeyboardAttachmentRequest(emptyList())))), "at least one row")
        assertContains(rejected(body(null, *Array(31) { listOf(cb()) })), "31 rows")
        assertContains(rejected(body(null, *Array(30) { List(7) { cb() } }, listOf(cb()))), "31 rows")
        assertContains(rejected(body(null, List(8) { cb() })), "Row 1 of the keyboard has 8 buttons")
        assertContains(rejected(body(null, *Array(30) { List(8) { cb() } })), "Keyboard has 240 buttons; Max allows at most 210")
        assertContains(rejected(body(null, listOf(cb()), List(8) { cb() })), "Row 2 of the keyboard has 8 buttons")
        for (wide in listOf<Button>(LinkButton("l", "https://max.ru"), OpenAppButton("o", "bot"), RequestGeoLocationButton("g"), RequestContactButton("c"))) {
            assertContains(rejected(body(null, List(4) { wide })), "4 buttons of types link, open_app, request_geo_location, request_contact")
        }
    }

    @Test
    fun `keyboards at the size limits are sent`(): Unit = runBlocking {
        repeat(3) { server.enqueue(json("""{"message":${messageJson()}}""")) }
        client.sendMessageToChat(1, body(null, *Array(30) { List(7) { cb() } }))
        client.sendMessageToChat(1, body(null, List(3) { LinkButton("l", "u") } + List(4) { cb() }))
        client.sendMessageToChat(1, body(null, listOf(RequestContactButton("c"), RequestGeoLocationButton("g"), OpenAppButton("o", "bot"), MessageButton("m"))))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `buttons keep to the length limits`(): Unit = runBlocking {
        fun rejects(button: Button): String = rejected(body(null, listOf(button)))
        assertContains(rejects(cb("")), "is 0 characters long; Max allows 1 to 128")
        assertContains(rejects(cb("a".repeat(129))), "is 129 characters long")
        assertContains(rejects(cb("😀".repeat(65))), "is 130 characters long")
        assertContains(rejects(UnknownButton("future", "")), "is 0 characters long")
        assertContains(rejects(CallbackButton("b", "p".repeat(1025))), "Payload of button \"b\" is 1025")
        assertContains(rejects(ClipboardButton("b", "p".repeat(1025))), "Payload of button \"b\" is 1025")
        assertContains(rejects(LinkButton("b", "u".repeat(2049))), "URL of button \"b\" is 2049")
        assertContains(rejects(OpenAppButton("b", "bot", "p".repeat(513))), "is 513 characters long; Max allows at most 512")
        assertContains(rejects(OpenAppButton("b", "bot", "a.b")), "may hold only A-Z, a-z, 0-9, _ and -: \"a.b\"")
        assertContains(rejects(OpenAppButton("b", "bot", "я")), "may hold only")
        assertContains(rejects(cb("x".repeat(200))), "\"" + "x".repeat(40) + "…\"")

        repeat(1) { server.enqueue(json("""{"message":${messageJson()}}""")) }
        client.sendMessageToChat(
            1,
            body(
                null,
                listOf(cb("a".repeat(128)), CallbackButton("b", "p".repeat(1024)), ClipboardButton("c", "p".repeat(1024))),
                listOf(LinkButton("d", "u".repeat(2048)), OpenAppButton("e", "bot", "Az09_-".repeat(85) + "ab"), OpenAppButton("f", "bot")),
                listOf(UnknownButton("future", "g")),
            ),
        )
        assertEquals(1, server.requestCount)
    }

    // -------------------------------------------------------- command limits

    @Test
    fun `commands keep to the limits`(): Unit = runBlocking {
        fun rejects(commands: List<BotCommand>): String =
            assertFailsWith<IllegalArgumentException> { runBlocking { client.editMyCommands(commands) } }.message!!
        assertContains(rejects(List(33) { BotCommand("c$it") }), "33 commands; Max allows at most 32")
        assertContains(rejects(listOf(BotCommand(""))), "is 0 characters long; Max allows 1 to 64")
        assertContains(rejects(listOf(BotCommand("c".repeat(65)))), "is 65 characters long")
        assertContains(rejects(listOf(BotCommand("c", ""))), "Description of command c is 0 characters long; Max allows 1 to 128")
        assertContains(rejects(listOf(BotCommand("c", "d".repeat(129)))), "is 129 characters long")
        assertEquals(0, server.requestCount)

        repeat(2) { server.enqueue(json("""{"commands":[]}""")) }
        client.editMyCommands(List(32) { BotCommand("c".repeat(64), "d".repeat(128)) })
        client.editMyCommands(listOf(BotCommand("c"), BotCommand("d", "x")))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `syncCommands is checked as well`() {
        val bot = MaxBot(client)
        bot.onCommand("x".repeat(65), "Too long") { }
        assertFailsWith<IllegalArgumentException> { bot.syncCommandsBlocking() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `limits are public constants`() {
        assertEquals(4000, MaxLimits.TEXT_LENGTH)
        assertEquals(210, MaxLimits.KEYBOARD_BUTTONS)
        assertEquals(30, MaxLimits.KEYBOARD_ROWS)
        assertEquals(7, MaxLimits.BUTTONS_PER_ROW)
        assertEquals(3, MaxLimits.WIDE_BUTTONS_PER_ROW)
        assertEquals(128, MaxLimits.BUTTON_TEXT_LENGTH)
        assertEquals(1024, MaxLimits.BUTTON_PAYLOAD_LENGTH)
        assertEquals(2048, MaxLimits.LINK_URL_LENGTH)
        assertEquals(512, MaxLimits.OPEN_APP_PAYLOAD_LENGTH)
        assertEquals(32, MaxLimits.COMMANDS)
        assertEquals(64, MaxLimits.COMMAND_NAME_LENGTH)
        assertEquals(128, MaxLimits.COMMAND_DESCRIPTION_LENGTH)
    }

    // ------------------------------------------------------------ rate limit

    @Test
    fun `the Max preset follows the limits of Max`() {
        val max = RateLimit.MAX
        assertEquals(30.0, max.requestsPerSecond)
        assertEquals(1, max.burst)
        assertNull(max.perChatRequestsPerSecond)
        assertEquals(2.0, max.perChatAnswersPerSecond)
        assertEquals(1, max.perChatAnswersBurst)
        assertEquals(
            "RateLimit(requestsPerSecond=30.0, burst=1, perChatRequestsPerSecond=null, perChatBurst=1, " +
                "perChatAnswersPerSecond=2.0, perChatAnswersBurst=1)",
            max.toString(),
        )
        val plain = RateLimit(10.0, perChatRequestsPerSecond = 1.0, perChatBurst = 2)
        assertNull(plain.perChatAnswersPerSecond)
        val answers = plain.withPerChatAnswers(3.0, burst = 2)
        assertEquals(listOf(10.0, 10.0, 1.0, 3.0), listOf(answers.requestsPerSecond, answers.burst.toDouble(), answers.perChatRequestsPerSecond, answers.perChatAnswersPerSecond))
        assertEquals(2, answers.perChatBurst)
        assertEquals(2, answers.perChatAnswersBurst)
        assertNull(answers.withPerChatAnswers(null).perChatAnswersPerSecond)
        assertEquals(1, plain.withPerChatAnswers(1.0).perChatAnswersBurst)
        assertFailsWith<IllegalArgumentException> { plain.withPerChatAnswers(0.0) }
        assertFailsWith<IllegalArgumentException> { plain.withPerChatAnswers(1.0, burst = 0) }
    }

    @Test
    fun `answers wait per chat in virtual time`() = runTest {
        val limiter = RateLimiter(RateLimit(1000.0, burst = 100).withPerChatAnswers(2.0)) { currentTime * 1_000_000 }
        val log = mutableListOf<String>()
        launch { repeat(3) { limiter.acquire(7, answer = true); log += "answer ${currentTime}" } }
        launch { repeat(2) { limiter.acquire(8, answer = true); log += "other ${currentTime}" } }
        launch { repeat(2) { limiter.acquire(7); log += "message ${currentTime}" } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("answer 0", "other 0", "message 0", "message 0", "answer 500", "other 500", "answer 1000"), log)
        assertEquals(2, limiter.trackedAnswerChats)
        assertEquals(0, limiter.trackedChats)
    }

    @Test
    fun `answers without a per-chat limit only wait for the global one`() = runTest {
        val limiter = RateLimiter(RateLimit(1000.0, burst = 100, perChatRequestsPerSecond = 1.0)) { currentTime * 1_000_000 }
        repeat(2) { limiter.acquire(7, answer = true) }
        assertEquals(1000, currentTime)
        assertEquals(0, limiter.trackedAnswerChats)
    }

    @Test
    fun `old presses are forgotten`() {
        val chats = CallbackChats()
        for (i in 0..CallbackChats.MAX_CALLBACKS) chats.remember("cb$i", i.toLong())
        assertEquals(CallbackChats.MAX_CALLBACKS, chats.size)
        assertNull(chats.chatOf("cb0"))
        assertEquals(5L, chats.chatOf("cb5"))
        assertNull(chats.chatOf(null))
    }

    @Test
    fun `the bot tells the client the chat of a press`(): Unit = runBlocking {
        val limited = MaxClient(
            MaxClientConfig.builder(TOKEN).baseUrl(server.url("/").toString()).retryPolicy(RetryPolicy.NONE)
                .rateLimit(RateLimit(1000.0, burst = 100).withPerChatAnswers(1000.0, burst = 10)).build(),
        )
        limited.use {
            val bot = MaxBot(limited)
            bot.onCallback { answer("ok") }
            repeat(2) { server.enqueue(json(SUCCESS)) }
            bot.processUpdate(callbackUpdate("cb1", 55))
            bot.processUpdate(callbackUpdate("cb2", null)) // the message was deleted: the chat is unknown
            assertEquals(55L, limited.transport.callbackChats.chatOf("cb1"))
            assertEquals(1, limited.transport.callbackChats.size)
            assertEquals(1, limited.transport.limiter!!.trackedAnswerChats)
            assertEquals("callback_id=cb1", server.take().url.query)
        }
        // Without a limit of answers the presses are not remembered.
        val bot = MaxBot(client)
        bot.onCallback { }
        bot.processUpdate(callbackUpdate("cb3", 55))
        assertEquals(0, client.transport.callbackChats.size)
        val noAnswers = MaxClient(MaxClientConfig(TOKEN, baseUrl = server.url("/").toString(), rateLimit = RateLimit(1000.0)))
        noAnswers.use {
            MaxBot(noAnswers).apply { onCallback { } }.processUpdate(callbackUpdate("cb4", 55))
            assertEquals(0, noAnswers.transport.callbackChats.size)
        }
    }

    // ------------------------------------------------------------- long text

    private fun sent(count: Int): List<Map<String, Any?>> = List(count) {
        @Suppress("UNCHECKED_CAST")
        MaxJson.mapper.readValue(server.take().bodyText, Map::class.java) as Map<String, Any?>
    }

    @Test
    fun `long text goes as several messages with attachments in the last one`(): Unit = runBlocking {
        val paragraph = "word ".repeat(500).trim() // 2499 characters
        val body = message {
            markdown("**$paragraph**\n\n$paragraph")
            replyTo("mid.0")
            notify(false)
            keyboard { row { callback("OK", "ok") } }
        }
        repeat(2) { server.enqueue(json("""{"message":${messageJson(mid = "mid.$it")}}""")) }
        val messages = client.sendLongMessageToChat(1, body)
        assertEquals(listOf("mid.0", "mid.1"), messages.map { it.body!!.mid })
        val (first, second) = sent(2)
        assertEquals("**$paragraph**", first["text"])
        assertEquals(paragraph, second["text"])
        assertEquals(listOf("markdown", "markdown"), listOf(first["format"], second["format"]))
        assertEquals(listOf(false, false), listOf(first["notify"], second["notify"]))
        assertNotNull(first["link"])
        assertNull(second["link"])
        assertNull(first["attachments"])
        assertNotNull(second["attachments"])
    }

    @Test
    fun `text that fits goes as one message`(): Unit = runBlocking {
        repeat(3) { server.enqueue(json("""{"message":${messageJson()}}""")) }
        assertEquals(1, client.sendLongMessageToUser(7, "short").size)
        assertEquals("user_id=7", server.take().url.query)
        assertEquals(1, client.sendLongMessage(NewMessageBody(attachments = listOf(LocationAttachmentRequest(1.0, 2.0))), chatId = 1).size)
        assertEquals(1, client.sendLongMessage(NewMessageBody(text = "a".repeat(4000)), chatId = 1, disableLinkPreview = true).size)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `long text is checked before the first part is sent`(): Unit = runBlocking {
        val long = "a".repeat(5000)
        assertFailsWith<IllegalArgumentException> { client.sendLongMessage(NewMessageBody(text = long)) }
        assertFailsWith<IllegalArgumentException> { client.sendLongMessage(NewMessageBody(text = long), userId = 1, chatId = 2) }
        assertFailsWith<IllegalArgumentException> { client.sendLongMessageToChat(1, body(long, List(8) { cb() })) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `every facade sends long text`(): Unit = runBlocking {
        val long = "a ".repeat(2500)
        repeat(16) { server.enqueue(json("""{"message":${messageJson()}}""")) }
        val blocking = client.blocking()
        val async = client.async()
        assertEquals(2, client.sendLongMessageToUser(1, NewMessageBody(text = long)).size)
        assertEquals(2, blocking.sendLongMessage(NewMessageBody(text = long), chatId = 1).size)
        assertEquals(2, blocking.sendLongMessageToChat(1, long).size)
        assertEquals(2, blocking.sendLongMessageToChat(1, NewMessageBody(text = long)).size)
        assertEquals(2, blocking.sendLongMessageToUser(1, long).size)
        assertEquals(2, blocking.sendLongMessageToUser(1, NewMessageBody(text = long)).size)
        assertEquals(2, async.sendLongMessage(NewMessageBody(text = long), chatId = 1).get().size)
        assertEquals(2, async.sendLongMessageToChat(1, long).get().size)
        assertEquals(16, server.requestCount)
        repeat(10) { server.enqueue(json("""{"message":${messageJson()}}""")) }
        assertEquals(2, blocking.sendLongMessage(NewMessageBody(text = long), 1).size)
        assertEquals(2, async.sendLongMessage(NewMessageBody(text = long), 1).get().size)
        assertEquals(2, async.sendLongMessageToChat(1, NewMessageBody(text = long)).get().size)
        assertEquals(2, async.sendLongMessageToUser(1, long).get().size)
        assertEquals(2, async.sendLongMessageToUser(1, NewMessageBody(text = long)).get().size)
    }

    @Test
    fun `handlers reply with long text`(): Unit = runBlocking {
        val bot = MaxBot(client)
        val long = "a ".repeat(2500)
        val seen = CopyOnWriteArrayList<Int>()
        bot.onMessage { seen += replyLong(long).size; seen += replyLongBlocking(long).size; seen += replyLongBlocking(NewMessageBody(text = long)).size }
        bot.onCallback { seen += replyLong { text(long) }.size }
        bot.on<Update> { replyLong(long) }
        repeat(8) { server.enqueue(json("""{"message":${messageJson()}}""")) }
        bot.processUpdate(MaxJson.parseUpdate("""{"update_type":"message_created","timestamp":1,"message":${messageJson(chatId = 5)}}"""))
        bot.processUpdate(callbackUpdate("cb", null)) // no chat: the reply goes to the user
        assertEquals(listOf(2, 2, 2, 2), seen)
        repeat(6) { assertEquals("chat_id=5", server.take().url.query) }
        assertEquals("user_id=3", server.take().url.query)

        val errors = CopyOnWriteArrayList<Throwable>()
        bot.onError { error, _ -> errors += error }
        bot.processUpdate(MaxJson.parseUpdate("""{"update_type":"unheard_of","timestamp":1}"""))
        assertIs<IllegalStateException>(errors.single())
    }

    // ------------------------------------------------------- webhook upkeep

    private val events = CopyOnWriteArrayList<WebhookResubscribeEvent>()
    private val listener = object : MaxEventListener {
        override fun onWebhookResubscribe(event: WebhookResubscribeEvent) {
            events += event
        }
    }

    private fun webhookBot(): MaxBot = MaxBot(client, BotConfig(eventListener = listener)).apply { onMessage { } }

    @Test
    fun `a subscription that is still there is left alone`(): Unit = runBlocking {
        server.enqueue(json("""{"subscriptions":[{"url":"https://other","time":1},{"url":"https://bot/hook","time":1}]}"""))
        assertFalse(webhookBot().ensureWebhook("https://bot/hook", "secret"))
        assertEquals(1, server.requestCount)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a dropped subscription is renewed and reported`(): Unit = runBlocking {
        server.enqueue(json("""{"subscriptions":[{"url":"https://other","time":1}]}"""))
        server.enqueue(json(SUCCESS))
        assertTrue(webhookBot().ensureWebhookBlocking("https://bot/hook", "secret"))
        server.take()
        val subscribe = server.take()
        assertEquals("POST", subscribe.method)
        assertContains(subscribe.bodyText, "\"url\":\"https://bot/hook\"")
        assertContains(subscribe.bodyText, "\"secret\":\"secret\"")
        assertEquals(WebhookResubscribeEvent("https://bot/hook", null), events.single())

        server.enqueue(json("""{"subscriptions":[]}"""))
        server.enqueue(json(SUCCESS))
        assertTrue(webhookBot().ensureWebhookBlocking("https://bot/hook"))
    }

    @Test
    fun `a failed renewal is reported and thrown`(): Unit = runBlocking {
        server.enqueue(json("""{"subscriptions":[]}"""))
        server.enqueue(json("""{"success":false,"message":"bad url"}"""))
        val refused = assertFailsWith<MaxException> { webhookBot().ensureWebhook("https://bot/hook") }
        assertEquals("Cannot subscribe to https://bot/hook again: bad url", refused.message)
        assertSame(refused, events.single().error)

        server.enqueue(json("""{"subscriptions":[]}"""))
        server.enqueue(json("""{"code":"bad","message":"no"}""", code = 400))
        assertFailsWith<MaxApiException> { webhookBot().ensureWebhook("https://bot/hook") }
        assertIs<MaxApiException>(events[1].error)

        // Without a listener the failure is still thrown.
        server.enqueue(json("""{"subscriptions":[]}"""))
        server.enqueue(json("""{"code":"bad","message":"no"}""", code = 400))
        assertFailsWith<MaxApiException> { MaxBot(client).apply { onMessage { } }.ensureWebhook("https://bot/hook") }
    }

    @Test
    fun `listeners get webhook events`() {
        val event = WebhookResubscribeEvent("https://x", null)
        MaxEventListener.NONE.onWebhookResubscribe(event)
        val other = CopyOnWriteArrayList<String>()
        val second = object : MaxEventListener {
            override fun onRequest(event: RequestEvent) {}
            override fun onUpdate(event: UpdateEvent) {}
            override fun onWebhookResubscribe(event: WebhookResubscribeEvent) {
                other += event.url
            }
        }
        MaxEventListener.composite(listOf(listener, second)).onWebhookResubscribe(event)
        assertEquals(listOf(event), events)
        assertEquals(listOf("https://x"), other)
    }
}
