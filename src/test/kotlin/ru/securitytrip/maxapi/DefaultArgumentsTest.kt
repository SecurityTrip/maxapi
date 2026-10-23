package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.java.MaxAsyncClient
import ru.securitytrip.maxapi.java.MaxBlockingClient
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.NewCommentBody
import ru.securitytrip.maxapi.model.message.NewMessageBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Methods called with every optional parameter omitted must not send those parameters at all,
 * so the server applies its own defaults.
 */
class DefaultArgumentsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = server.client()
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.close()
    }

    private class Case(
        val name: String,
        val response: String,
        val path: String,
        /** The only query parameters expected: the required ones. */
        val query: Map<String, String>,
        val body: String? = null,
        val kotlin: suspend MaxClient.() -> Any?,
        val blocking: MaxBlockingClient.() -> Any?,
        val async: MaxAsyncClient.() -> CompletableFuture<*>,
    )

    private val cases = listOf(
        Case(
            "pinMessage", SUCCESS, "/chats/1/pin", emptyMap(), """{"message_id":"m"}""",
            kotlin = { pinMessage(1, "m") }, blocking = { pinMessage(1, "m") }, async = { pinMessage(1, "m") },
        ),
        Case(
            "getMembers", """{"members":[]}""", "/chats/1/members", emptyMap(),
            kotlin = { getMembers(1) }, blocking = { getMembers(1) }, async = { getMembers(1) },
        ),
        Case(
            "removeMember", SUCCESS, "/chats/1/members", mapOf("user_id" to "2"),
            kotlin = { removeMember(1, 2) }, blocking = { removeMember(1, 2) }, async = { removeMember(1, 2) },
        ),
        Case(
            "subscribe", SUCCESS, "/subscriptions", emptyMap(), """{"url":"https://hook"}""",
            kotlin = { subscribe("https://hook") }, blocking = { subscribe("https://hook") }, async = { subscribe("https://hook") },
        ),
        Case(
            "getUpdates", """{"updates":[]}""", "/updates", emptyMap(),
            kotlin = { getUpdates() }, blocking = { getUpdates() }, async = { getUpdates() },
        ),
        Case(
            "getMessages", """{"messages":[]}""", "/messages", emptyMap(),
            kotlin = { getMessages() }, blocking = { getMessages() }, async = { getMessages() },
        ),
        Case(
            "sendMessage", """{"message":${messageJson()}}""", "/messages", mapOf("chat_id" to "1"), """{"text":"x"}""",
            kotlin = { sendMessage(X, chatId = 1) }, blocking = { sendMessage(X, chatId = 1) }, async = { sendMessage(X, chatId = 1) },
        ),
        Case(
            "answerOnCallback", SUCCESS, "/answers", mapOf("callback_id" to "cb"), """{"notification":"n"}""",
            kotlin = { answerOnCallback("cb", ANSWER) }, blocking = { answerOnCallback("cb", ANSWER) }, async = { answerOnCallback("cb", ANSWER) },
        ),
        Case(
            "getComments", """{"messages":[]}""", "/messages/m/comments", emptyMap(),
            kotlin = { getComments("m") }, blocking = { getComments("m") }, async = { getComments("m") },
        ),
        Case(
            "sendComment",
            """{"message":{"recipient":{"chat_id":1},"timestamp":1,"body":{"mid":"c","seq":1}}}""",
            "/messages/m/comments", emptyMap(), """{"text":"c"}""",
            kotlin = { sendComment("m", COMMENT) }, blocking = { sendComment("m", COMMENT) }, async = { sendComment("m", COMMENT) },
        ),
    )

    @TestFactory
    fun `kotlin client`() = cases.map { case ->
        DynamicTest.dynamicTest(case.name) { verify(case) { runBlocking { case.kotlin(client) } } }
    }

    @TestFactory
    fun `blocking facade`() = cases.map { case ->
        DynamicTest.dynamicTest(case.name) { verify(case) { case.blocking(client.blocking()) } }
    }

    @TestFactory
    fun `async facade`() = cases.map { case ->
        DynamicTest.dynamicTest(case.name) { verify(case) { case.async(client.async()).get(5, TimeUnit.SECONDS) } }
    }

    private fun verify(case: Case, call: () -> Any?) {
        server.enqueue(json(case.response))
        call()
        val request = server.take()
        assertEquals(case.path, request.url.encodedPath)
        assertEquals(case.query, request.url.queryParameterNames.associateWith { request.url.queryParameter(it)!! })
        if (case.body != null) assertEquals(case.body, request.bodyText)
    }

    private companion object {
        val X = NewMessageBody(text = "x")
        val ANSWER = CallbackAnswer(notification = "n")
        val COMMENT = NewCommentBody(text = "c")
    }
}
