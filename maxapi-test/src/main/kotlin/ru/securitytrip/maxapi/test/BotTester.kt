package ru.securitytrip.maxapi.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.update.Update
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a bot in tests without a network: feeds it updates and records the API calls its handlers
 * make.
 *
 * ```kotlin
 * val tester = BotTester()
 * registerHandlers(tester.bot)                 // your code that sets up the bot
 *
 * tester.sendMessage("/start")
 * assertEquals("Hello!", tester.sentMessages.single().text)
 *
 * tester.pressButton("like")
 * assertEquals("Thanks!", tester.callbackAnswers.single().answer.notification)
 * ```
 *
 * ```java
 * BotTester tester = new BotTester();
 * registerHandlers(tester.getBot());
 * tester.sendMessageBlocking("/start");
 * assertEquals("Hello!", tester.getSentMessages().get(0).getText());
 * ```
 *
 * Calls are answered with plausible defaults: `POST /messages` returns the sent message,
 * `GET /me` returns a bot named [BOT_USERNAME], and other calls return `{"success":true}`.
 * Use [stub] for calls whose response your handlers read, e.g. `getChat`.
 *
 * Handler exceptions are rethrown by the `send*` methods, so a failing handler fails the test.
 * Updates are handled one at a time, in the calling coroutine or thread.
 *
 * @param config settings of the bot under test.
 */
public class BotTester @JvmOverloads constructor(config: BotConfig = BotConfig()) : Closeable {
    private val mapper: ObjectMapper = MaxJson.createMapper()
    private val recorded = CopyOnWriteArrayList<ApiCall>()
    private val stubs = ConcurrentHashMap<String, Stub>()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private val ids = AtomicLong()

    private class Stub(val status: Int, val json: String)

    /** Client of the bot; every call is recorded and answered locally. */
    public val client: MaxClient = MaxClient(
        MaxClientConfig(
            token = "test-token",
            baseUrl = BASE_URL,
            httpClient = OkHttpClient.Builder().addInterceptor { chain -> respond(chain.request()) }.build(),
            retryPolicy = RetryPolicy.NONE,
            attachmentReadyRetries = 0,
            attachmentReadyDelay = Duration.ZERO,
        ),
    )

    /** The bot under test: register handlers on it. Its username is [BOT_USERNAME]. */
    public val bot: MaxBot = MaxBot(client, config).apply {
        username = BOT_USERNAME
        onError { error, _ -> errors += error }
    }

    /** Every API call made so far, in order. */
    public val calls: List<ApiCall> get() = recorded.toList()

    /** Messages sent with `sendMessage` (and `reply`), in order. */
    public val sentMessages: List<SentMessage>
        get() = recorded.filter { it.method == "POST" && it.path == "/messages" }.map {
            SentMessage(it.query["chat_id"]?.toLong(), it.query["user_id"]?.toLong(), it.parseBody(NewMessageBody::class.java))
        }

    /** Answers to button presses (`answer`, `editMessage`), in order. */
    public val callbackAnswers: List<CallbackAnswerCall>
        get() = recorded.filter { it.method == "POST" && it.path == "/answers" }.map {
            CallbackAnswerCall(it.query.getValue("callback_id"), it.parseBody(CallbackAnswer::class.java))
        }

    /** Forgets the recorded calls, e.g. between the steps of a test. */
    public fun clearCalls() {
        recorded.clear()
    }

    /**
     * Answers calls of [method] to [path] (like `"GET"`, `"/chats/42"`) with [json] and HTTP [status].
     * A status other than 2xx makes the client throw the matching `MaxApiException`.
     */
    @JvmOverloads
    public fun stub(method: String, path: String, json: String, status: Int = 200) {
        stubs[key(method, path)] = Stub(status, json)
    }

    // ------------------------------------------------------------- updates

    /**
     * Passes [update] to the bot and returns when its handler completes.
     * @throws Throwable the first exception thrown by the handler, if any.
     */
    public suspend fun send(update: Update) {
        errors.clear()
        bot.processUpdate(update)
        errors.firstOrNull()?.let { throw it }
    }

    /** Sends a text message from [userId] in [chatId], see [send]. */
    public suspend fun sendMessage(
        text: String,
        chatId: Long = TestUpdates.CHAT_ID,
        userId: Long = TestUpdates.USER_ID,
        chatType: ChatType = ChatType.DIALOG,
    ) {
        send(TestUpdates.message(text, chatId, userId, chatType, "mid.${ids.incrementAndGet()}"))
    }

    /** Presses a callback button with [payload], see [send]. */
    public suspend fun pressButton(payload: String, chatId: Long = TestUpdates.CHAT_ID, userId: Long = TestUpdates.USER_ID) {
        send(TestUpdates.callback(payload, chatId, userId, "cb.${ids.incrementAndGet()}"))
    }

    /** A user starting the bot, optionally with a deep link [payload], see [send]. */
    public suspend fun startBot(payload: String? = null, userId: Long = TestUpdates.USER_ID) {
        send(TestUpdates.botStarted(payload, userId))
    }

    /** Blocking version of [send] for Java tests. */
    public fun sendBlocking(update: Update): Unit = runBlocking { send(update) }

    /** Blocking version of [sendMessage] for Java tests. */
    @JvmOverloads
    public fun sendMessageBlocking(
        text: String,
        chatId: Long = TestUpdates.CHAT_ID,
        userId: Long = TestUpdates.USER_ID,
        chatType: ChatType = ChatType.DIALOG,
    ): Unit = runBlocking { sendMessage(text, chatId, userId, chatType) }

    /** Blocking version of [pressButton] for Java tests. */
    @JvmOverloads
    public fun pressButtonBlocking(payload: String, chatId: Long = TestUpdates.CHAT_ID, userId: Long = TestUpdates.USER_ID): Unit =
        runBlocking { pressButton(payload, chatId, userId) }

    /** Blocking version of [startBot] for Java tests. */
    @JvmOverloads
    public fun startBotBlocking(payload: String? = null, userId: Long = TestUpdates.USER_ID): Unit =
        runBlocking { startBot(payload, userId) }

    /** Closes the bot and its client. */
    override fun close() {
        bot.close()
        client.close()
    }

    // ---------------------------------------------------------------------

    private fun respond(request: Request): Response {
        val url = request.url
        val body = request.body?.let { requestBody -> Buffer().also { requestBody.writeTo(it) }.readUtf8() }
        val call = ApiCall(
            request.method,
            url.encodedPath,
            url.queryParameterNames.associateWith { url.queryParameterValues(it).joinToString(",") },
            body,
            mapper,
        )
        recorded += call
        val stub = stubs[key(call.method, call.path)]
        val (status, json) = if (stub != null) stub.status to stub.json else 200 to defaultResponse(call)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Test")
            .body(json.toResponseBody(JSON))
            .build()
    }

    private fun defaultResponse(call: ApiCall): String = when (key(call.method, call.path)) {
        "POST /messages" -> sentMessageResponse(call)
        "GET /me" -> """{"user_id":$BOT_USER_ID,"first_name":"Test bot","username":"$BOT_USERNAME","is_bot":true}"""
        else -> """{"success":true}"""
    }

    private fun sentMessageResponse(call: ApiCall): String {
        val message = mapper.createObjectNode()
        message.putObject("sender").put("user_id", BOT_USER_ID).put("first_name", "Test bot").put("is_bot", true)
        val recipient: ObjectNode = message.putObject("recipient")
        call.query["chat_id"]?.let { recipient.put("chat_id", it.toLong()) }
        call.query["user_id"]?.let { recipient.put("user_id", it.toLong()) }
        message.put("timestamp", System.currentTimeMillis())
        val body = message.putObject("body").put("mid", "mid.bot.${ids.incrementAndGet()}").put("seq", ids.get())
        call.parseBody(NewMessageBody::class.java).text?.let { body.put("text", it) }
        return mapper.writeValueAsString(mapper.createObjectNode().set<JsonNode>("message", message))
    }

    private fun key(method: String, path: String) = "${method.uppercase()} $path"

    public companion object {
        /** Username of the bot under test, so commands like `/start@test_bot` reach it. */
        public const val BOT_USERNAME: String = "test_bot"

        /** User identifier of the bot under test. */
        public const val BOT_USER_ID: Long = 1_000

        private const val BASE_URL = "http://maxapi.test/"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * An API call recorded by [BotTester].
 *
 * @property method HTTP method, like `POST`.
 * @property path request path, like `/messages`.
 * @property query query parameters.
 * @property body request body, `null` if there is none.
 */
public class ApiCall internal constructor(
    public val method: String,
    public val path: String,
    public val query: Map<String, String>,
    public val body: String?,
    private val mapper: ObjectMapper,
) {
    /** The body as a JSON tree, `null` if there is no body. */
    public val json: JsonNode? get() = body?.let { mapper.readTree(it) }

    /** The body parsed as [type]. */
    public fun <T> parseBody(type: Class<T>): T = mapper.readValue(body, type)

    override fun toString(): String = "$method $path$query${body?.let { " $it" }.orEmpty()}"
}

/**
 * A message the bot sent.
 *
 * @property chatId target chat, if the message was sent to a chat.
 * @property userId target user, if the message was sent to a user.
 * @property body the message as the bot built it.
 */
public data class SentMessage(val chatId: Long?, val userId: Long?, val body: NewMessageBody) {
    /** Text of the message. */
    val text: String? get() = body.text
}

/**
 * An answer to a button press.
 *
 * @property callbackId the press that was answered.
 * @property answer notification and/or new message content.
 */
public data class CallbackAnswerCall(val callbackId: String, val answer: CallbackAnswer)
