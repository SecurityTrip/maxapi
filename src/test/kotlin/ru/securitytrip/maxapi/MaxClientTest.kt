package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxNetworkException
import ru.securitytrip.maxapi.client.NotFoundException
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.client.TooManyRequestsException
import ru.securitytrip.maxapi.client.UnauthorizedException
import ru.securitytrip.maxapi.client.UploadSource
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.attachment.PhotoToken
import ru.securitytrip.maxapi.model.attachment.UploadType
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.update.UpdateType
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxClientTest {
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

    @Test
    fun `getMyInfo sends token and parses bot info`(): Unit = runBlocking {
        server.enqueue(json("""{"user_id":7,"first_name":"Bot","username":"echo_bot","is_bot":true,"commands":[{"name":"start"}]}"""))

        val me = client.getMyInfo()

        assertEquals(7L, me.userId)
        assertEquals("echo_bot", me.username)
        assertEquals("start", me.commands!!.single().name)
        val request = server.take()
        assertEquals("GET", request.method)
        assertEquals("/me", request.url.encodedPath)
        assertEquals(TOKEN, request.headers["Authorization"])
    }

    @Test
    fun `sendMessage posts body with chat_id query`(): Unit = runBlocking {
        server.enqueue(json("""{"message":${messageJson(chatId = 55, text = "Hello")}}"""))

        val sent = client.sendMessageToChat(55, message { markdown("**Hello**"); notify(false) })

        assertEquals("Hello", sent.body?.text)
        val request = server.take()
        assertEquals("POST", request.method)
        assertEquals("/messages", request.url.encodedPath)
        assertEquals("55", request.url.queryParameter("chat_id"))
        assertNull(request.url.queryParameter("user_id"))
        assertEquals("""{"text":"**Hello**","notify":false,"format":"markdown"}""", request.bodyText)
    }

    @Test
    fun `sendMessage requires exactly one recipient`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.sendMessage(NewMessageBody(text = "x")) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.sendMessage(NewMessageBody(text = "x"), userId = 1, chatId = 2) }
        }
    }

    @Test
    fun `list query parameters are comma separated and path segments encoded`(): Unit = runBlocking {
        server.enqueue(json("""{"members":[{"user_id":1,"is_admin":true,"is_owner":false,"permissions":["write","new_perm"]}],"marker":3}"""))
        server.enqueue(json("""{"updates":[],"marker":null}"""))
        server.enqueue(json(messageJson()))

        val members = client.getMembers(-10, userIds = listOf(1, 2, 3))
        client.getUpdates(timeout = 0, types = listOf(UpdateType.MESSAGE_CREATED, UpdateType.BOT_STARTED))
        client.getMessageById("mid.a/b")

        assertEquals(3L, members.marker)
        assertEquals(2, members.members.single().permissions!!.size)
        val membersRequest = server.take()
        assertEquals("/chats/-10/members", membersRequest.url.encodedPath)
        assertEquals("1,2,3", membersRequest.url.queryParameter("user_ids"))
        val updatesRequest = server.take()
        assertEquals("message_created,bot_started", updatesRequest.url.queryParameter("types"))
        assertEquals("0", updatesRequest.url.queryParameter("timeout"))
        assertNull(updatesRequest.url.queryParameter("marker"))
        assertEquals("/messages/mid.a%2Fb", server.take().url.encodedPath)
    }

    @Test
    fun `api errors become typed exceptions`(): Unit = runBlocking {
        server.enqueue(json("""{"code":"verify.token","message":"Invalid access_token"}""", code = 401))
        server.enqueue(json("""{"code":"not.found","message":"Chat 5 not found"}""", code = 404))
        server.enqueue(json("""{"code":"chat.denied","message":"Forwarding is prohibited"}""", code = 403))

        val unauthorized = assertFailsWith<UnauthorizedException> { client.getMyInfo() }
        assertEquals("verify.token", unauthorized.errorCode)
        assertEquals("Invalid access_token", unauthorized.errorMessage)
        assertFailsWith<NotFoundException> { client.getChat(5) }
        val forbidden = assertFailsWith<MaxApiException> { client.sendMessageToUser(1, "x") }
        assertEquals(403, forbidden.httpStatus)
        assertEquals("chat.denied", forbidden.errorCode)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `retries 503 and 429 then succeeds`(): Unit = runBlocking {
        server.enqueue(json("""{"code":"service.unavailable","message":"later"}""", code = 503))
        server.enqueue(json("""{"code":"too.many.requests","message":"slow down"}""", code = 429))
        server.enqueue(json(SUCCESS))

        val result = client.sendAction(1, SenderAction.TYPING_ON)

        assertTrue(result.success)
        assertEquals(3, server.requestCount)
        assertEquals("""{"action":"typing_on"}""", server.take().bodyText)
    }

    @Test
    fun `gives up after max retries`() {
        repeat(3) { server.enqueue(json("""{"code":"limit","message":"slow down"}""", code = 429)) }
        val error = assertFailsWith<TooManyRequestsException> { runBlocking { client.getMyInfo() } }
        assertEquals("limit", error.errorCode)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `network errors are wrapped`() {
        val port = server.port
        server.close()
        val offline = MaxClient(
            MaxClientConfig(TOKEN, baseUrl = "http://127.0.0.1:$port/", retryPolicy = RetryPolicy.NONE),
        )
        offline.use {
            assertFailsWith<MaxNetworkException> { runBlocking { it.getMyInfo() } }
        }
    }

    @Test
    fun `retries while attachment is not ready`(): Unit = runBlocking {
        server.enqueue(json("""{"code":"attachment.not.ready","message":"Key: errors.process.attachment.file.not.processed"}""", code = 400))
        server.enqueue(json("""{"message":${messageJson()}}"""))

        client.sendMessageToUser(9, NewMessageBody(text = "file"))

        assertEquals(2, server.requestCount)
        assertEquals("9", server.take().url.queryParameter("user_id"))
    }

    @Test
    fun `uploads image without leaking the token to the upload host`(): Unit = runBlocking {
        server.enqueue(json("""{"url":"${server.url("/upload.do?id=1")}"}"""))
        server.enqueue(json("""{"photos":{"abc":{"token":"photo-token"}}}"""))

        val attachment = client.uploadImage(UploadSource.of("png".toByteArray(), "cat.png"))

        assertEquals(mapOf("abc" to PhotoToken("photo-token")), attachment.payload.photos)
        val endpointRequest = server.take()
        assertEquals("/uploads", endpointRequest.url.encodedPath)
        assertEquals("image", endpointRequest.url.queryParameter("type"))
        assertEquals(TOKEN, endpointRequest.headers["Authorization"])

        val upload = server.take()
        assertEquals("/upload.do", upload.url.encodedPath)
        assertNull(upload.headers["Authorization"])
        assertTrue(upload.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        assertTrue(upload.bodyText.contains("""name="data"; filename="cat.png""""))
        assertTrue(upload.bodyText.contains("image/png"))
    }

    @Test
    fun `video upload uses the token issued with the upload url`(): Unit = runBlocking {
        server.enqueue(json("""{"url":"${server.url("/upload.do")}","token":"video-token"}"""))
        server.enqueue(MockResponseOk("<retval>1</retval>"))

        val attachment = client.upload(UploadType.VIDEO, UploadSource.of("mp4".byteInputStream(), "movie.mp4"))

        assertEquals("video", attachment.type)
        assertEquals("video-token", (attachment as ru.securitytrip.maxapi.model.attachment.VideoAttachmentRequest).payload.token)
    }

    @Test
    fun `file upload reads token from upload response`(): Unit = runBlocking {
        server.enqueue(json("""{"url":"${server.url("/upload.do")}"}"""))
        server.enqueue(json("""{"token":"file-token"}"""))

        val attachment = client.uploadFile(UploadSource.of("doc".toByteArray(), "doc.pdf"))

        assertEquals("file-token", attachment.payload.token)
    }

    @Test
    fun `answerOnCallback sends callback_id and answer`(): Unit = runBlocking {
        server.enqueue(json(SUCCESS))

        client.answerOnCallback("cb-1", CallbackAnswer(notification = "Done"))

        val request = server.take()
        assertEquals("/answers", request.url.encodedPath)
        assertEquals("cb-1", request.url.queryParameter("callback_id"))
        assertEquals("""{"notification":"Done"}""", request.bodyText)
    }

    @Test
    fun `subscribe sends update types`(): Unit = runBlocking {
        server.enqueue(json(SUCCESS))

        client.subscribe("https://bot.example/hook", "s3cret", listOf(UpdateType.MESSAGE_CREATED))

        assertEquals(
            """{"url":"https://bot.example/hook","secret":"s3cret","update_types":["message_created"]}""",
            server.take().bodyText,
        )
    }

    @Test
    fun `blocking and async wrappers delegate to the client`() {
        server.enqueue(json("""{"user_id":1,"first_name":"Bot"}"""))
        server.enqueue(json("""{"chat_id":2,"type":"chat","status":"active"}"""))

        assertEquals(1L, client.blocking().getMyInfo().userId)
        assertEquals(2L, client.async().getChat(2).get().chatId)
    }

    @Test
    fun `config toString does not expose the token`() {
        val config = MaxClientConfig.builder("super-secret").attachmentReadyDelay(Duration.ofSeconds(2)).build()
        assertTrue("super-secret" !in config.toString())
    }

    @Suppress("TestFunctionName")
    private fun MockResponseOk(body: String) = json(body).newBuilder().setHeader("Content-Type", "text/xml").build()
}
