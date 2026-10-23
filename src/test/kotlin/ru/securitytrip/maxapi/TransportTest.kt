package ru.securitytrip.maxapi

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxException
import ru.securitytrip.maxapi.client.MaxNetworkException
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.client.TooManyRequestsException
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.message.NewMessageBody
import java.time.Duration
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

class TransportTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    /** A client whose OkHttp does not retry by itself, so only the library's retries are observed. */
    private fun client(
        retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 2, initialDelay = Duration.ofMillis(1)),
        http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        userAgent: String = MaxClientConfig.DEFAULT_USER_AGENT,
    ) = MaxClient(
        MaxClientConfig(
            token = TOKEN,
            baseUrl = server.url("/").toString(),
            httpClient = http,
            retryPolicy = retryPolicy,
            attachmentReadyRetries = 2,
            attachmentReadyDelay = Duration.ofMillis(1),
            userAgent = userAgent,
        ),
    )

    private fun disconnect(): MockResponse = MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()

    @Test
    fun `sends user agent and accept headers`(): Unit = runBlocking {
        server.enqueue(json(BOT))
        client(userAgent = "my-bot/1.0").use { it.getMyInfo() }

        val request = server.take()
        assertEquals("my-bot/1.0", request.headers["User-Agent"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun `retries GET after a network error`(): Unit = runBlocking {
        server.enqueue(disconnect())
        server.enqueue(json(BOT))

        val me = client().use { it.getMyInfo() }

        assertEquals(1L, me.userId)
    }

    @Test
    fun `does not retry POST after a network error`() {
        server.enqueue(disconnect())
        server.enqueue(json(SUCCESS))

        client().use { client ->
            assertFailsWith<MaxNetworkException> { runBlocking { client.sendAction(1, SenderAction.TYPING_ON) } }
            // The second response is still queued, so the POST was not sent again.
            assertTrue(runBlocking { client.sendAction(1, SenderAction.TYPING_ON) }.success)
        }
    }

    @Test
    fun `network error message names the operation and keeps the cause`() {
        server.enqueue(disconnect())
        val error = client(retryPolicy = RetryPolicy.NONE).use { client ->
            assertFailsWith<MaxNetworkException> { runBlocking { client.getChat(5) } }
        }
        assertTrue(error.message!!.startsWith("GET /chats/5 failed"), error.message)
        assertIs<java.io.IOException>(error.cause)
    }

    @Test
    fun `connection dropped while reading the body is a retryable network error`(): Unit = runBlocking {
        server.enqueue(json(BOT).newBuilder().onResponseBody(SocketEffect.CloseSocket()).build())
        server.enqueue(json(BOT))

        val me = client().use { it.getMyInfo() }

        assertEquals(1L, me.userId)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `network errors during upload are wrapped`() {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        server.enqueue(json("""{"url":"http://127.0.0.1:$closedPort/upload.do"}"""))

        val error = client().use { client ->
            assertFailsWith<MaxNetworkException> {
                runBlocking { client.uploadFile(ru.securitytrip.maxapi.client.UploadSource.of(byteArrayOf(1), "a.txt")) }
            }
        }
        assertTrue(error.message!!.startsWith("Upload of a.txt failed"), error.message)
    }

    @Test
    fun `retries 502 and 504`(): Unit = runBlocking {
        server.enqueue(json("bad gateway", code = 502))
        server.enqueue(json("timeout", code = 504))
        server.enqueue(json(BOT))

        client().use { it.getMyInfo() }

        assertEquals(3, server.requestCount)
    }

    @Test
    fun `does not retry other errors`() {
        server.enqueue(json("""{"code":"internal","message":"oops"}""", code = 500))
        server.enqueue(json(BOT))

        val error = client().use { client -> assertFailsWith<MaxApiException> { runBlocking { client.getMyInfo() } } }

        assertEquals(500, error.httpStatus)
        assertEquals("internal", error.errorCode)
        assertEquals("HTTP 500 [internal]: oops", error.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `non-JSON error body becomes the error message`() {
        server.enqueue(json("<html>Bad Gateway</html>", code = 400))

        val error = client().use { client -> assertFailsWith<MaxApiException> { runBlocking { client.getMyInfo() } } }

        assertNull(error.errorCode)
        assertEquals("<html>Bad Gateway</html>", error.errorMessage)
        assertEquals("<html>Bad Gateway</html>", error.responseBody)
    }

    @Test
    fun `error key from the error field is used when code is absent`() {
        server.enqueue(json("""{"error":"bad.request","message":"wrong"}""", code = 400))

        val error = client().use { client -> assertFailsWith<MaxApiException> { runBlocking { client.getMyInfo() } } }

        assertEquals("bad.request", error.errorCode)
    }

    @Test
    fun `invalid success body throws MaxException`() {
        server.enqueue(json("not json"))

        val error = client().use { client -> assertFailsWith<MaxException> { runBlocking { client.getMyInfo() } } }

        assertFalse(error is MaxApiException)
        assertTrue(error.message!!.startsWith("GET /me: cannot parse response"), error.message)
    }

    @Test
    fun `Retry-After longer than maxDelay fails at once instead of retrying early`() {
        server.enqueue(json("""{"code":"limit","message":"slow"}""", code = 429).newBuilder().setHeader("Retry-After", "60").build())
        server.enqueue(json(BOT))
        val policy = RetryPolicy(maxRetries = 3, initialDelay = Duration.ofMillis(1), maxDelay = Duration.ofSeconds(30))

        val started = System.nanoTime()
        val error = client(retryPolicy = policy).use { client ->
            assertFailsWith<TooManyRequestsException> { runBlocking { client.getMyInfo() } }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(Duration.ofSeconds(60), error.retryAfter)
        assertTrue(elapsedMs < 5_000, "waited $elapsedMs ms")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Retry-After within maxDelay is waited for`() {
        server.enqueue(json("""{"code":"limit","message":"slow"}""", code = 429).newBuilder().setHeader("Retry-After", "0").build())
        server.enqueue(json(BOT))

        val me = client().use { client -> runBlocking { client.getMyInfo() } }

        assertEquals(1L, me.userId)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `gateway errors are not retried for POST`() {
        for (code in listOf(502, 504)) {
            server.enqueue(json("""{"code":"gateway","message":"upstream"}""", code = code))
            server.enqueue(json(SUCCESS))
            client().use { client ->
                val error = assertFailsWith<MaxApiException> { runBlocking { client.sendAction(1, SenderAction.TYPING_ON) } }
                assertEquals(code, error.httpStatus)
                // The queued success is still there: the POST was sent once.
                assertTrue(runBlocking { client.sendAction(1, SenderAction.TYPING_ON) }.success)
            }
        }
    }

    @Test
    fun `Retry-After accepts delay-seconds and HTTP-dates`() {
        val now = java.time.Instant.parse("2026-10-21T07:28:00Z")
        assertEquals(Duration.ofSeconds(5), ru.securitytrip.maxapi.client.parseRetryAfter(" 5 ", now))
        assertEquals(Duration.ofSeconds(30), ru.securitytrip.maxapi.client.parseRetryAfter("Wed, 21 Oct 2026 07:28:30 GMT", now))
        assertEquals(Duration.ZERO, ru.securitytrip.maxapi.client.parseRetryAfter("Wed, 21 Oct 2026 07:27:00 GMT", now))
        assertNull(ru.securitytrip.maxapi.client.parseRetryAfter("-1", now))
        assertNull(ru.securitytrip.maxapi.client.parseRetryAfter("soon", now))
        assertNull(ru.securitytrip.maxapi.client.parseRetryAfter(null, now))
        assertTrue(ru.securitytrip.maxapi.client.parseRetryAfter("Wed, 21 Oct 2099 07:28:00 GMT")!! > Duration.ZERO)
    }

    @Test
    fun `retry delays grow exponentially and are capped`() {
        val policy = RetryPolicy(maxRetries = 5, initialDelay = Duration.ofMillis(100), maxDelay = Duration.ofMillis(500), multiplier = 3.0)
        assertEquals(listOf(100L, 300L, 500L, 500L), (0..3).map { policy.delayMillis(it, null) })
        assertEquals(listOf(100L, 300L, 500L, 500L), (0..3).map { policy.backoffMillis(it) })
        assertEquals(200L, policy.delayMillis(0, Duration.ofMillis(200)))
        assertEquals(500L, policy.delayMillis(0, Duration.ofMillis(500)))
        assertNull(policy.delayMillis(0, Duration.ofSeconds(10)))
        assertEquals(listOf(500L, 1000L, 2000L), (0..2).map { RetryPolicy.DEFAULT.backoffMillis(it) })
        assertEquals(0, RetryPolicy.NONE.maxRetries)
    }

    @Test
    fun `attachment not ready is detected by code or message and retried a limited number of times`() {
        repeat(3) {
            server.enqueue(json("""{"code":"bad.request","message":"attachment.not.ready"}""", code = 400))
        }

        val error = client().use { client ->
            assertFailsWith<MaxApiException> { runBlocking { client.sendMessageToChat(1, NewMessageBody(text = "x")) } }
        }

        assertTrue(error.isAttachmentNotReady)
        assertEquals(3, server.requestCount) // first attempt + attachmentReadyRetries = 2
    }

    @Test
    fun `editMessage and answerOnCallback also wait for attachments`(): Unit = runBlocking {
        server.enqueue(json("""{"code":"attachment.not.ready","message":"processing"}""", code = 400))
        server.enqueue(json(SUCCESS))
        server.enqueue(json("""{"code":"attachment.not.ready","message":"processing"}""", code = 400))
        server.enqueue(json(SUCCESS))

        client().use {
            it.editMessage("mid", NewMessageBody(text = "x"))
            it.answerOnCallback("cb", "x")
        }

        assertEquals(4, server.requestCount)
    }

    @Test
    fun `long polling gets a longer read timeout than other requests`(): Unit = runBlocking {
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(500, TimeUnit.MILLISECONDS).build()
        val slow = { body: String -> json(body).newBuilder().headersDelay(1_000, TimeUnit.MILLISECONDS).build() }
        server.enqueue(slow("""{"updates":[]}"""))
        server.enqueue(slow(BOT))

        client(retryPolicy = RetryPolicy.NONE, http = http).use { client ->
            assertEquals(0, client.getUpdates(timeout = 1).updates.size)
            assertFailsWith<MaxNetworkException> { client.getMyInfo() }
        }
    }

    @Test
    fun `cancelling the coroutine cancels the HTTP call`(): Unit = runBlocking {
        server.enqueue(json(BOT).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())

        client().use { client ->
            val call = async { client.getMyInfo() }
            delay(200)
            call.cancel()
            withTimeout(2_000) { call.join() }
            assertTrue(call.isCancelled)
        }
    }

    @Test
    fun `closing the client keeps a user-supplied OkHttpClient usable`() {
        val http = OkHttpClient()
        client(http = http).close()
        assertFalse(http.dispatcher.executorService.isShutdown)
    }

    @Test
    fun `closing the client cancels pending async calls`() {
        server.enqueue(json(BOT).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val client = client()
        val future = client.async().getMyInfo()
        client.close()
        runBlocking { withTimeout(2_000) { while (!future.isDone) delay(10) } }
        assertTrue(future.isCancelled || future.isCompletedExceptionally)
    }

    private companion object {
        const val BOT = """{"user_id":1,"first_name":"Bot"}"""
    }
}
