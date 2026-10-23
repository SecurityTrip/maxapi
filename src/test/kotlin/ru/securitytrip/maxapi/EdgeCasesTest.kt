package ru.securitytrip.maxapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxException
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.client.TooManyRequestsException
import ru.securitytrip.maxapi.client.UploadSource
import ru.securitytrip.maxapi.dsl.keyboard
import ru.securitytrip.maxapi.model.attachment.Attachment
import ru.securitytrip.maxapi.model.keyboard.Button
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.markup.MarkupElement
import ru.securitytrip.maxapi.model.markup.StrongMarkup
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.Update
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Less common paths: missing optional data, odd server responses and bot lifecycle corners. */
class EdgeCasesTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient
    private val mapper = MaxJson.createMapper()

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

    private fun fastBot(client: MaxClient = this.client) = MaxBot(
        client,
        BotConfig(minPollInterval = Duration.ofMillis(5), pollTimeoutSeconds = 0, maxErrorBackoff = Duration.ofMillis(10)),
    )

    private fun pollingDispatcher(pages: (Int) -> MockResponse) = object : Dispatcher() {
        private var polls = 0

        @Synchronized
        override fun dispatch(request: RecordedRequest): MockResponse =
            if (request.url.encodedPath == "/updates") pages(++polls) else json(SUCCESS)
    }

    // ------------------------------------------------------------------- client

    @Test
    fun `empty collections are not sent as query parameters`(): Unit = runBlocking {
        server.enqueue(json("""{"members":[]}"""))
        client.getMembers(1, userIds = emptyList())
        assertNull(server.take().url.queryParameter("user_ids"))
    }

    @Test
    fun `blank callback id is rejected before any request`() {
        assertFailsWith<IllegalArgumentException> { runBlocking { client.answerOnCallback(" ", "x") } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `error without message or body`() {
        server.enqueue(json("", code = 400))
        val error = assertFailsWith<MaxApiException> { runBlocking { client.getMyInfo() } }
        assertNull(error.errorCode)
        assertNull(error.errorMessage)
        assertEquals("HTTP 400", error.message)
        assertFalse(error.isAttachmentNotReady)
    }

    @Test
    fun `error with code but without message`() {
        server.enqueue(json("""{"code":"x.y"}""", code = 400))
        val error = assertFailsWith<MaxApiException> { runBlocking { client.getMyInfo() } }
        assertEquals("HTTP 400 [x.y]: {\"code\":\"x.y\"}", error.message)
        assertFalse(error.isAttachmentNotReady)
    }

    @Test
    fun `invalid Retry-After values are ignored`() {
        val noRetries = MaxClient(MaxClientConfig(TOKEN, baseUrl = server.url("/").toString(), retryPolicy = RetryPolicy.NONE))
        noRetries.use {
            for (value in listOf("soon", "-5", " 7 ")) {
                server.enqueue(json("""{"code":"limit","message":"slow"}""", code = 429).newBuilder().setHeader("Retry-After", value).build())
                val error = assertFailsWith<TooManyRequestsException> { runBlocking { it.getMyInfo() } }
                assertEquals(if (value == " 7 ") Duration.ofSeconds(7) else null, error.retryAfter, value)
            }
        }
    }

    @Test
    fun `long polling reuses clients whose read timeout is already long enough`(): Unit = runBlocking {
        for (http in listOf(OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build(), OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).build())) {
            server.enqueue(json("""{"updates":[]}"""))
            MaxClient(MaxClientConfig(TOKEN, baseUrl = server.url("/").toString(), httpClient = http)).use {
                assertEquals(0, it.getUpdates().updates.size)
            }
        }
    }

    @Test
    fun `image upload with a non-object photos field falls back to the token`(): Unit = runBlocking {
        server.enqueue(json("""{"url":"${server.url("/upload")}"}"""))
        server.enqueue(json("""{"photos":[],"token":"t"}"""))
        assertEquals("t", client.uploadImage(UploadSource.of(byteArrayOf(1), "a.png")).payload.token)
    }

    @Test
    fun `blank endpoint token falls back to the upload response`(): Unit = runBlocking {
        server.enqueue(json("""{"url":"${server.url("/upload")}","token":""}"""))
        server.enqueue(json("""{"token":"from-response"}"""))
        assertEquals("from-response", client.uploadVideo(UploadSource.of(byteArrayOf(1), "a.mp4")).payload.token)
    }

    @Test
    fun `upload without any token fails`() {
        server.enqueue(json("""{"url":"${server.url("/upload")}"}"""))
        server.enqueue(json("{}"))
        val error = assertFailsWith<MaxException> { runBlocking { client.uploadAudio(UploadSource.of(byteArrayOf(1), "a.mp3")) } }
        assertTrue(error.message!!.startsWith("audio upload response contains no token"), error.message)
    }

    @Test
    fun `stream sources are one-shot, file sources are not`() {
        assertTrue(UploadSource.of("x".byteInputStream(), "a.txt").body.isOneShot())
        val file = File.createTempFile("source", ".txt").apply { deleteOnExit() }
        assertFalse(UploadSource.of(file).body.isOneShot())
        assertEquals(-1, UploadSource.of("x".byteInputStream(), "a.txt").body.contentLength())
    }

    // -------------------------------------------------------------------- models

    @Test
    fun `unknown types are not equal to different content`() {
        val attachment = mapper.readValue("""{"type":"x","a":1}""", Attachment::class.java)
        assertNotEquals<Any>(attachment, mapper.readValue("""{"type":"y","a":1}""", Attachment::class.java))
        assertNotEquals<Any>(attachment, mapper.readValue("""{"type":"x","a":2}""", Attachment::class.java))
        assertNotEquals<Any>(attachment, "x")

        val button = mapper.readValue("""{"type":"x","text":"t","a":1}""", Button::class.java)
        assertNotEquals<Any>(button, mapper.readValue("""{"type":"y","text":"t","a":1}""", Button::class.java))
        assertNotEquals<Any>(button, mapper.readValue("""{"type":"x","text":"u","a":1}""", Button::class.java))
        assertNotEquals<Any>(button, CallbackButton("t", "p"))

        val markup = mapper.readValue("""{"type":"x","from":1,"length":2,"a":1}""", MarkupElement::class.java)
        for (other in listOf(
            """{"type":"y","from":1,"length":2,"a":1}""",
            """{"type":"x","from":0,"length":2,"a":1}""",
            """{"type":"x","from":1,"length":3,"a":1}""",
            """{"type":"x","from":1,"length":2,"a":2}""",
        )) {
            assertNotEquals<Any>(markup, mapper.readValue(other, MarkupElement::class.java), other)
        }
        assertNotEquals<Any>(markup, StrongMarkup(1, 2))

        val update = MaxJson.parseUpdate("""{"update_type":"x","timestamp":1,"a":1}""")
        for (other in listOf(
            """{"update_type":"y","timestamp":1,"a":1}""",
            """{"update_type":"x","timestamp":2,"a":1}""",
            """{"update_type":"x","timestamp":1,"a":2}""",
        )) {
            assertNotEquals<Any>(update, MaxJson.parseUpdate(other), other)
        }
        assertNotEquals<Any>(update, "x")
        assertNull(MaxJson.parseUpdate("""{"update_type":"x","timestamp":1,"chat_id":"not a number"}""").chatId)
    }

    @Test
    fun `callback chat comes from the message recipient`() {
        val withChat = MaxJson.parseUpdate(
            """{"update_type":"message_callback","timestamp":1,"callback":{"timestamp":1,"callback_id":"c","user":{"user_id":1}},
                "message":{"recipient":{"chat_id":5},"timestamp":1}}""",
        ) as MessageCallbackUpdate
        val withoutChatId = MaxJson.parseUpdate(
            """{"update_type":"message_callback","timestamp":1,"callback":{"timestamp":1,"callback_id":"c","user":{"user_id":1}},
                "message":{"recipient":{"user_id":2},"timestamp":1}}""",
        )
        assertEquals(5L, withChat.chatId)
        assertNull(withoutChatId.chatId)
    }

    // ----------------------------------------------------------------------- bot

    @Test
    fun `message without a body has no text and replies without a quote`(): Unit = runBlocking {
        server.enqueue(json("""{"message":${messageJson()}}"""))
        val bot = fastBot()
        val seen = CompletableDeferred<Pair<String?, String?>>()
        bot.onMessage {
            seen.complete(text to messageId)
            replyToMessage("no quote")
        }

        bot.processUpdate(MaxJson.parseUpdate("""{"update_type":"message_created","timestamp":1,"message":{"recipient":{"chat_id":3},"timestamp":1}}"""))

        assertEquals(null to null, seen.await())
        assertEquals("""{"text":"no quote"}""", server.take().bodyText)
    }

    @Test
    fun `kotlin defaults of context and bot helpers`(): Unit = runBlocking {
        repeat(3) { server.enqueue(json(SUCCESS)) }
        val bot = fastBot()
        bot.onCallback {
            editMessage(NewMessageBody(text = "e"))
            answerBlocking("blocking")
        }
        bot.processUpdate(
            MaxJson.parseUpdate("""{"update_type":"message_callback","timestamp":1,"callback":{"timestamp":1,"callback_id":"c","user":{"user_id":1}}}"""),
        )
        bot.setWebhook("https://hook")

        assertEquals("""{"message":{"text":"e"}}""", server.take().bodyText)
        assertEquals("""{"notification":"blocking"}""", server.take().bodyText)
        assertEquals("""{"url":"https://hook","update_types":["message_callback"]}""", server.take().bodyText)
        assertEquals(1, keyboard { row { requestGeoLocation("Where") } }.payload.buttons.size)
    }

    @Test
    fun `webhook rejects missing and wrong secrets for every body type`(): Unit = runBlocking {
        val handler = fastBot().webhookHandler("s3cret")
        val body = """{"update_type":"x","timestamp":1}"""
        assertFalse(handler.handle(body, null))
        assertFalse(handler.handle(body.toByteArray(), "wrong"))
        assertFalse(handler.handleBlocking(body.toByteArray(), "wrong"))
        assertFalse(handler.handleAsync(body.toByteArray(), "wrong"))
    }

    @Test
    fun `default error handler logs handler and polling errors`(): Unit = runBlocking {
        server.dispatcher = pollingDispatcher { poll ->
            when (poll) {
                1 -> json("""{"code":"internal","message":"oops"}""", code = 500)
                else -> json("""{"updates":[{"update_type":"message_created","timestamp":1,"message":${messageJson()}}]}""")
            }
        }
        val noRetries = MaxClient(MaxClientConfig(TOKEN, baseUrl = server.url("/").toString(), retryPolicy = RetryPolicy.NONE))
        val bot = fastBot(noRetries)
        val handled = CompletableDeferred<Unit>()
        bot.onMessage {
            handled.complete(Unit)
            error("handler failure goes to the default logger")
        }

        val job = bot.start()
        withTimeout(5_000) { handled.await() }
        bot.stop()
        job.join()
        bot.processUpdate(messageUpdateWithoutChat())
        noRetries.close()
    }

    @Test
    fun `startBlocking returns normally after stop`() {
        server.dispatcher = pollingDispatcher { json("""{"updates":[]}""") }
        val bot = fastBot()
        bot.onMessage { }
        val stopper = thread {
            while (server.requestCount == 0) Thread.sleep(5)
            bot.stop()
        }

        bot.startBlocking()
        stopper.join()
    }

    @Test
    fun `polling keeps the marker when a page has none, and backs off on repeated errors`(): Unit = runBlocking {
        val markers = Collections.synchronizedList(mutableListOf<String?>())
        server.dispatcher = object : Dispatcher() {
            private var polls = 0

            @Synchronized
            override fun dispatch(request: RecordedRequest): MockResponse {
                markers += request.url.queryParameter("marker")
                return when (++polls) {
                    1 -> json("""{"updates":[],"marker":5}""")
                    2 -> json("""{"updates":[]}""")
                    3, 4 -> json("""{"code":"internal","message":"oops"}""", code = 500)
                    else -> json("""{"updates":[]}""")
                }
            }
        }
        val noRetries = MaxClient(MaxClientConfig(TOKEN, baseUrl = server.url("/").toString(), retryPolicy = RetryPolicy.NONE))
        val bot = fastBot(noRetries)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        bot.onError { error, _ -> errors += error }
        bot.onMessage { }

        val job = bot.start()
        withTimeout(5_000) { while (markers.size < 5) kotlinx.coroutines.delay(5) }
        bot.stop()
        job.join()
        noRetries.close()

        assertEquals(listOf(null, "5", "5", "5", "5"), markers.take(5))
        assertEquals(2, errors.size)
    }

    @Test
    fun `polled updates without a chat are distributed by user or to the first worker`(): Unit = runBlocking {
        server.dispatcher = pollingDispatcher { poll ->
            if (poll == 1) {
                json(
                    """{"updates":[
                        {"update_type":"message_callback","timestamp":1,"callback":{"timestamp":1,"callback_id":"c","user":{"user_id":7}}},
                        {"update_type":"mystery","timestamp":1}
                    ]}""",
                )
            } else {
                json("""{"updates":[]}""")
            }
        }
        val bot = fastBot()
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val done = CompletableDeferred<Unit>()
        bot.onUpdate(Update::class.java) {
            seen += update.updateType
            if (seen.size == 2) done.complete(Unit)
        }

        val job = bot.start()
        withTimeout(5_000) { done.await() }
        bot.stop()
        job.join()

        assertEquals(setOf("message_callback", "mystery"), seen.toSet())
    }

    private fun messageUpdateWithoutChat() = MaxJson.parseUpdate(
        """{"update_type":"message_created","timestamp":1,"message":{"recipient":{"user_id":1},"timestamp":1}}""",
    )
}
