package ru.securitytrip.maxapi

import com.fasterxml.jackson.databind.JsonNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachment
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.keyboard.Button
import ru.securitytrip.maxapi.model.keyboard.Keyboard
import ru.securitytrip.maxapi.model.message.Callback
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.message.MessageBody
import ru.securitytrip.maxapi.model.message.Recipient
import ru.securitytrip.maxapi.model.update.MessageCallbackUpdate
import ru.securitytrip.maxapi.model.update.MessageCreatedUpdate
import ru.securitytrip.maxapi.model.user.User
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** A client whose calls are recorded and answered in memory, for tests of bot features that make many calls. */
class FakeApi : Closeable {
    class Call(val method: String, val path: String, val query: Map<String, String>, val body: String?) {
        val json: JsonNode? get() = body?.let { MaxJson.mapper.readTree(it) }
        override fun toString(): String = "$method $path$query ${body.orEmpty()}"
    }

    class Reply(val code: Int, val body: String = SUCCESS, val retryAfter: String? = null)

    val calls = CopyOnWriteArrayList<Call>()

    /** Answers a call; `null` gives the default answer. */
    @Volatile
    var respond: (Call) -> Reply? = { null }

    val client = MaxClient(
        MaxClientConfig(
            token = TOKEN,
            baseUrl = "http://fake.test/",
            httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val url = request.url
                val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
                val call = Call(request.method, url.encodedPath, url.queryParameterNames.associateWith { url.queryParameter(it)!! }, body)
                calls += call
                val reply = respond(call) ?: Reply(200, if (call.method == "POST" && call.path == "/messages") """{"message":${messageJson()}}""" else SUCCESS)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.code).message("Fake")
                    .apply { reply.retryAfter?.let { header("Retry-After", it) } }
                    .body(reply.body.toResponseBody("application/json".toMediaType())).build()
            }.build(),
            retryPolicy = RetryPolicy.NONE,
            attachmentReadyRetries = 0,
            attachmentReadyDelay = Duration.ZERO,
        ),
    )

    /** Calls to `POST /messages`. */
    val sent: List<Call> get() = calls.filter { it.method == "POST" && it.path == "/messages" }

    /** Texts of the sent messages, in order. */
    val texts: List<String?> get() = sent.map { it.json?.path("text")?.textValue() }

    /** Notifications of the answers to button presses, in order. */
    val notifications: List<String?> get() = calls.filter { it.path == "/answers" }.map { it.json?.path("notification")?.textValue() }

    /** Payloads of the buttons of the last sent message, row by row. */
    val lastButtons: List<List<String>>
        get() = sent.last().json!!.path("attachments").first { it.path("type").asText() == "inline_keyboard" }
            .path("payload").path("buttons").map { row -> row.map { it.path("payload").asText() } }

    override fun close() = client.close()

    companion object {
        fun message(text: String?, chatId: Long = 100, userId: Long = 1, chatType: ChatType = ChatType.DIALOG, timestamp: Long = 1) =
            MessageCreatedUpdate(
                timestamp,
                Message(User(userId, "User"), Recipient(chatId = chatId, chatType = chatType), timestamp, body = MessageBody("mid.$timestamp", text = text)),
            )

        fun press(payload: String, keyboard: List<List<Button>>? = null, chatId: Long = 100, userId: Long = 1) = MessageCallbackUpdate(
            1,
            Callback(1, "cb", payload, User(userId, "User")),
            Message(
                null, Recipient(chatId = chatId, chatType = ChatType.DIALOG), 1,
                body = MessageBody("mid.k", attachments = keyboard?.let { listOf(InlineKeyboardAttachment(Keyboard(it))) }),
            ),
        )
    }
}
