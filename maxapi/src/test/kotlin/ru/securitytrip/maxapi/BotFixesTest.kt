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
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.MessageContext
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.bot.Filters
import ru.securitytrip.maxapi.dsl.Buttons
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest
import ru.securitytrip.maxapi.model.message.MessageLinkType
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.message.NewMessageLink
import ru.securitytrip.maxapi.model.update.Update
import ru.securitytrip.maxapi.model.update.UpdateType
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Error isolation, addressed commands, graceful stop and other bot lifecycle guarantees. */
class BotFixesTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient
    private lateinit var bot: MaxBot
    private val errors = CopyOnWriteArrayList<Throwable>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = server.client()
        bot = MaxBot(client, BotConfig(minPollInterval = Duration.ofMillis(5), pollTimeoutSeconds = 0))
        bot.onError { error, _ -> errors += error }
    }

    @AfterTest
    fun tearDown() {
        bot.close()
        client.close()
        server.close()
    }

    private fun message(text: String, chatId: Long = 1) =
        """{"update_type":"message_created","timestamp":1,"message":${messageJson(chatId = chatId, text = text)}}"""

    private fun pages(first: String) = object : Dispatcher() {
        private var polls = 0

        @Synchronized
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.url.encodedPath != "/updates" -> json(SUCCESS)
            ++polls == 1 -> json("""{"marker":1,"updates":[$first]}""")
            else -> json("""{"marker":1,"updates":[]}""")
        }
    }

    @Test
    fun `an Error thrown by a handler is reported and polling continues`(): Unit = runBlocking {
        server.dispatcher = pages("${message("/stats")},${message("after")}")
        val done = CompletableDeferred<Unit>()
        bot.onCommand("stats") { TODO("not written yet") }
        bot.onMessage { done.complete(Unit) }

        val job = bot.start()
        withTimeout(5_000) { done.await() }
        bot.stop()
        job.join()

        assertIs<NotImplementedError>(errors.single())
    }

    @Test
    fun `commands addressed to another bot are ignored`(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        bot.username = "my_bot"
        bot.onCommand("start") { seen += "start $commandArgs" }
        bot.onMessage { seen += "message $text" }

        bot.processUpdate(MaxJson.parseUpdate(message("/start@My_Bot mine")))
        bot.processUpdate(MaxJson.parseUpdate(message("/start@other_bot theirs")))
        bot.processUpdate(MaxJson.parseUpdate(message("/start plain")))

        assertEquals(listOf("start mine", "message /start@other_bot theirs", "start plain"), seen)
    }

    @Test
    fun `the bot username is fetched once when an addressed command arrives`(): Unit = runBlocking {
        server.enqueue(json("""{"user_id":1,"first_name":"Bot","username":"my_bot","is_bot":true}"""))
        val seen = mutableListOf<String>()
        bot.onCommand("start") { seen += "start" }

        bot.processUpdate(MaxJson.parseUpdate(message("/start")))
        assertEquals(0, server.requestCount) // no suffix, no need to know the username
        bot.processUpdate(MaxJson.parseUpdate(message("/start@my_bot")))
        bot.processUpdate(MaxJson.parseUpdate(message("/start@my_bot")))

        assertEquals("my_bot", bot.username)
        assertEquals(1, server.requestCount)
        assertEquals(listOf("start", "start", "start"), seen)
    }

    @Test
    fun `stop lets updates already received finish`(): Unit = runBlocking {
        server.dispatcher = pages("${message("a")},${message("b")},${message("c")}")
        val firstStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val handled = Collections.synchronizedList(mutableListOf<String?>())
        bot.onMessage {
            if (text == "a") {
                firstStarted.complete(Unit)
                gate.await()
            }
            handled += text
        }

        val job = bot.start()
        withTimeout(5_000) { firstStarted.await() }
        bot.stop()
        delay(50)
        assertTrue(job.isActive, "polling must wait for queued updates")
        gate.complete(Unit)
        withTimeout(5_000) { job.join() }

        assertEquals(listOf<String?>("a", "b", "c"), handled)
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `stop works for startPolling and right after start`(): Unit = runBlocking {
        server.dispatcher = pages("")
        bot.onMessage { }

        val immediate = bot.start()
        bot.stop()
        withTimeout(5_000) { immediate.join() }

        val direct = launch { bot.startPolling() }
        withTimeout(5_000) { while (server.requestCount == 0) delay(5) }
        bot.stop()
        withTimeout(5_000) { direct.join() }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `handleAsync after close throws so the webhook request fails`() {
        val handler = bot.webhookHandler()
        bot.close()

        assertFailsWith<IllegalStateException> { handler.handleAsync(message("late"), null) }
    }

    @Test
    fun `setWebhook needs handlers, and empty type lists are not sent`(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { bot.setWebhook("https://hook") }
        assertEquals(0, server.requestCount)

        server.enqueue(json(SUCCESS))
        client.subscribe("https://hook", updateTypes = emptyList<UpdateType>())
        assertEquals("""{"url":"https://hook"}""", server.take().bodyText)
    }

    @Test
    fun `one context per update is shared by filters and handlers`(): Unit = runBlocking {
        val seen = mutableListOf<MessageContext>()
        bot.onCommand("a") { }
        bot.onMessage({ seen += it; false }) { }
        bot.onMessage { seen += this }

        bot.processUpdate(MaxJson.parseUpdate(message("hello")))

        assertEquals(2, seen.size)
        assertSame(seen[0], seen[1])
    }

    @Test
    fun `contexts of different update kinds are not mixed`(): Unit = runBlocking {
        val kinds = mutableListOf<String>()
        bot.onUpdate(Update::class.java) { kinds += this::class.simpleName!! }
        bot.processUpdate(MaxJson.parseUpdate(message("x")))
        assertEquals(listOf("UpdateContext"), kinds)
    }

    @Test
    fun `an empty text, as Max sends for photos and stickers, counts as no text`(): Unit = runBlocking {
        val seen = mutableListOf<String>()
        bot.onMessage(Filters.hasText()) { seen += "text:$text" }
        bot.onMessage { seen += "none:${text ?: "null"}" }
        bot.processUpdate(MaxJson.parseUpdate(message("")))
        bot.processUpdate(MaxJson.parseUpdate(message("hi")))
        assertEquals(listOf("none:null", "text:hi"), seen)
    }

    @Test
    fun `messages without text, attachments or a forward are rejected before sending`(): Unit = runBlocking {
        for (body in listOf(NewMessageBody(), NewMessageBody(text = ""), NewMessageBody(text = "", attachments = emptyList()))) {
            val error = assertFailsWith<IllegalArgumentException> { client.sendMessageToChat(1, body) }
            assertTrue(error.message!!.contains("text, attachments or a forwarded message"))
        }
        assertFailsWith<IllegalArgumentException> {
            client.sendMessageToChat(1, NewMessageBody(link = NewMessageLink(MessageLinkType.REPLY, "mid.1")))
        }
        assertEquals(0, server.requestCount)

        val sent = listOf(
            NewMessageBody(text = "hi"),
            NewMessageBody(attachments = listOf(InlineKeyboardAttachmentRequest(listOf(listOf(Buttons.callback("a", "b")))))),
            NewMessageBody(link = NewMessageLink(MessageLinkType.FORWARD, "mid.1")),
        )
        for (body in sent) {
            server.enqueue(json("""{"message":${messageJson()}}"""))
            client.sendMessageToChat(1, body)
        }
        assertEquals(sent.size, server.requestCount)
    }
}
