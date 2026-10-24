package ru.securitytrip.maxapi

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.java.MaxAsyncClient
import ru.securitytrip.maxapi.java.MaxBlockingClient
import ru.securitytrip.maxapi.model.SimpleQueryResult
import ru.securitytrip.maxapi.model.chat.ChatAdmin
import ru.securitytrip.maxapi.model.chat.ChatAdminPermission
import ru.securitytrip.maxapi.model.chat.ChatMember
import ru.securitytrip.maxapi.model.chat.ChatMembersList
import ru.securitytrip.maxapi.model.chat.ChatPatch
import ru.securitytrip.maxapi.model.chat.ModifyMembersResult
import ru.securitytrip.maxapi.model.chat.SenderAction
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import ru.securitytrip.maxapi.model.message.CommentMessage
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.message.NewCommentBody
import ru.securitytrip.maxapi.model.message.NewMessageBody
import ru.securitytrip.maxapi.model.markup.TextFormat
import ru.securitytrip.maxapi.model.subscription.Subscription
import ru.securitytrip.maxapi.model.update.UpdateList
import ru.securitytrip.maxapi.model.update.UpdateType
import ru.securitytrip.maxapi.model.attachment.UploadEndpoint
import ru.securitytrip.maxapi.model.attachment.UploadType
import ru.securitytrip.maxapi.model.attachment.VideoAttachmentDetails
import ru.securitytrip.maxapi.model.chat.Chat
import ru.securitytrip.maxapi.model.user.BotCommand
import ru.securitytrip.maxapi.model.user.BotCommandsInfo
import ru.securitytrip.maxapi.model.user.BotInfo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every API method, called through each of the three facades, must produce the same HTTP request
 * and parse the same response. This also catches swapped arguments in the Java wrappers.
 */
class ClientMethodsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MaxClient
    private val mapper = MaxJson.createMapper()

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
        val method: String,
        val path: String,
        val query: Map<String, String> = emptyMap(),
        val body: String? = null,
        /** Checks the parsed result. */
        val check: (Any?) -> Unit = {},
        val kotlin: suspend MaxClient.() -> Any?,
        val blocking: MaxBlockingClient.() -> Any?,
        val async: MaxAsyncClient.() -> CompletableFuture<*>,
    )

    private val cases = listOf(
        Case(
            "getMyInfo", BOT, "GET", "/me",
            check = { assertEquals(1L, (it as BotInfo).userId) },
            kotlin = { getMyInfo() }, blocking = { getMyInfo() }, async = { getMyInfo() },
        ),
        Case(
            "editMyCommands", """{"commands":[{"name":"start","description":"Start"}]}""", "PATCH", "/me/commands",
            body = """{"commands":[{"name":"start","description":"Start"}]}""",
            check = { assertEquals("start", (it as BotCommandsInfo).commands!!.single().name) },
            kotlin = { editMyCommands(COMMANDS) }, blocking = { editMyCommands(COMMANDS) }, async = { editMyCommands(COMMANDS) },
        ),
        Case(
            "getChat", CHAT, "GET", "/chats/-5",
            check = { assertEquals(-5L, (it as Chat).chatId) },
            kotlin = { getChat(-5) }, blocking = { getChat(-5) }, async = { getChat(-5) },
        ),
        Case(
            "editChat", CHAT, "PATCH", "/chats/-5",
            body = """{"title":"New","notify":false}""",
            kotlin = { editChat(-5, PATCH) }, blocking = { editChat(-5, PATCH) }, async = { editChat(-5, PATCH) },
        ),
        Case(
            "sendAction", SUCCESS, "POST", "/chats/-5/actions",
            body = """{"action":"mark_seen"}""", check = ::assertSuccess,
            kotlin = { sendAction(-5, SenderAction.MARK_SEEN) },
            blocking = { sendAction(-5, SenderAction.MARK_SEEN) },
            async = { sendAction(-5, SenderAction.MARK_SEEN) },
        ),
        Case(
            "getPinnedMessage", """{"message":${messageJson()}}""", "GET", "/chats/-5/pin",
            check = { assertEquals("mid.1", (it as Message).body?.mid) },
            kotlin = { getPinnedMessage(-5) }, blocking = { getPinnedMessage(-5) }, async = { getPinnedMessage(-5) },
        ),
        Case(
            "pinMessage", SUCCESS, "PUT", "/chats/-5/pin",
            body = """{"message_id":"mid.1","notify":true}""",
            kotlin = { pinMessage(-5, "mid.1", true) },
            blocking = { pinMessage(-5, "mid.1", true) },
            async = { pinMessage(-5, "mid.1", true) },
        ),
        Case(
            "unpinMessage", SUCCESS, "DELETE", "/chats/-5/pin",
            kotlin = { unpinMessage(-5) }, blocking = { unpinMessage(-5) }, async = { unpinMessage(-5) },
        ),
        Case(
            "getMembership", MEMBER, "GET", "/chats/-5/members/me",
            check = { assertTrue((it as ChatMember).isAdmin) },
            kotlin = { getMembership(-5) }, blocking = { getMembership(-5) }, async = { getMembership(-5) },
        ),
        Case(
            "leaveChat", SUCCESS, "DELETE", "/chats/-5/members/me",
            kotlin = { leaveChat(-5) }, blocking = { leaveChat(-5) }, async = { leaveChat(-5) },
        ),
        Case(
            "getAdmins", MEMBERS, "GET", "/chats/-5/members/admins",
            check = { assertEquals(2L, (it as ChatMembersList).members.single().userId) },
            kotlin = { getAdmins(-5) }, blocking = { getAdmins(-5) }, async = { getAdmins(-5) },
        ),
        Case(
            "postAdmins", SUCCESS, "POST", "/chats/-5/members/admins",
            body = """{"admins":[{"user_id":2,"permissions":["write","pin_message"],"alias":"Boss"}]}""",
            kotlin = { postAdmins(-5, ADMINS) }, blocking = { postAdmins(-5, ADMINS) }, async = { postAdmins(-5, ADMINS) },
        ),
        Case(
            "deleteAdmins", SUCCESS, "DELETE", "/chats/-5/members/admins/2",
            kotlin = { deleteAdmins(-5, 2) }, blocking = { deleteAdmins(-5, 2) }, async = { deleteAdmins(-5, 2) },
        ),
        Case(
            "getMembers", MEMBERS, "GET", "/chats/-5/members", mapOf("marker" to "7", "count" to "50"),
            check = { assertEquals(7L, (it as ChatMembersList).marker) },
            kotlin = { getMembers(-5, marker = 7, count = 50) },
            blocking = { getMembers(-5, null, 7, 50) },
            async = { getMembers(-5, null, 7, 50) },
        ),
        Case(
            "addMembers",
            """{"success":false,"failed_user_ids":[3],"failed_user_details":[{"error_code":"add.participant.privacy","user_ids":[3]}]}""",
            "POST", "/chats/-5/members",
            body = """{"user_ids":[2,3]}""",
            check = {
                it as ModifyMembersResult
                assertEquals(setOf(3L), it.failedUserIds)
                assertEquals("add.participant.privacy", it.failedUserDetails!!.single().errorCode)
            },
            kotlin = { addMembers(-5, listOf(2, 3)) },
            blocking = { addMembers(-5, listOf(2, 3)) },
            async = { addMembers(-5, listOf(2, 3)) },
        ),
        Case(
            "removeMember", SUCCESS, "DELETE", "/chats/-5/members", mapOf("user_id" to "2", "block" to "true"),
            kotlin = { removeMember(-5, 2, block = true) },
            blocking = { removeMember(-5, 2, true) },
            async = { removeMember(-5, 2, true) },
        ),
        Case(
            "getSubscriptions", """{"subscriptions":[{"url":"https://x","time":1,"update_types":["bot_started"]}]}""",
            "GET", "/subscriptions",
            check = {
                @Suppress("UNCHECKED_CAST")
                val subscription = (it as List<Subscription>).single()
                assertEquals(setOf("bot_started"), subscription.updateTypes)
            },
            kotlin = { getSubscriptions() }, blocking = { getSubscriptions() }, async = { getSubscriptions() },
        ),
        Case(
            "subscribe", SUCCESS, "POST", "/subscriptions",
            body = """{"url":"https://bot.example/hook","secret":"s3cret","update_types":["message_created","bot_started"]}""",
            kotlin = { subscribe(HOOK, "s3cret", TYPES) },
            blocking = { subscribe(HOOK, "s3cret", TYPES) },
            async = { subscribe(HOOK, "s3cret", TYPES) },
        ),
        Case(
            "unsubscribe", SUCCESS, "DELETE", "/subscriptions", mapOf("url" to HOOK),
            kotlin = { unsubscribe(HOOK) }, blocking = { unsubscribe(HOOK) }, async = { unsubscribe(HOOK) },
        ),
        Case(
            "getUpdates", """{"updates":[],"marker":4}""", "GET", "/updates",
            mapOf("limit" to "10", "timeout" to "5", "marker" to "3", "types" to "message_created,bot_started"),
            check = { assertEquals(4L, (it as UpdateList).marker) },
            kotlin = { getUpdates(10, 5, 3, TYPES) },
            blocking = { getUpdates(10, 5, 3, TYPES) },
            async = { getUpdates(10, 5, 3, TYPES) },
        ),
        Case(
            "getUploadUrl", """{"url":"https://upload.example","token":"t"}""", "POST", "/uploads", mapOf("type" to "audio"),
            check = { assertEquals("t", (it as UploadEndpoint).token) },
            kotlin = { getUploadUrl(UploadType.AUDIO) },
            blocking = { getUploadUrl(UploadType.AUDIO) },
            async = { getUploadUrl(UploadType.AUDIO) },
        ),
        Case(
            "getMessages", """{"messages":[${messageJson()}]}""", "GET", "/messages",
            mapOf("chat_id" to "-5", "message_ids" to "a,b", "before" to "20", "after" to "10", "count" to "5"),
            check = { assertEquals(1, (it as List<*>).size) },
            kotlin = { getMessages(-5, listOf("a", "b"), 20, 10, 5) },
            blocking = { getMessages(-5, listOf("a", "b"), 20, 10, 5) },
            async = { getMessages(-5, listOf("a", "b"), 20, 10, 5) },
        ),
        Case(
            "sendMessage", SENT, "POST", "/messages", mapOf("user_id" to "1", "disable_link_preview" to "true"),
            body = """{"text":"hi"}""", check = ::assertMessage,
            kotlin = { sendMessage(HI, userId = 1, disableLinkPreview = true) },
            blocking = { sendMessage(HI, 1, null, true) },
            async = { sendMessage(HI, 1, null, true) },
        ),
        Case(
            "sendMessageToChat(text)", SENT, "POST", "/messages", mapOf("chat_id" to "-5"),
            body = """{"text":"hi"}""", check = ::assertMessage,
            kotlin = { sendMessageToChat(-5, "hi") },
            blocking = { sendMessageToChat(-5, "hi") },
            async = { sendMessageToChat(-5, "hi") },
        ),
        Case(
            "sendMessageToChat(body)", SENT, "POST", "/messages", mapOf("chat_id" to "-5"),
            body = """{"text":"hi"}""",
            kotlin = { sendMessageToChat(-5, HI) }, blocking = { sendMessageToChat(-5, HI) }, async = { sendMessageToChat(-5, HI) },
        ),
        Case(
            "sendMessageToUser(text)", SENT, "POST", "/messages", mapOf("user_id" to "1"),
            body = """{"text":"hi"}""",
            kotlin = { sendMessageToUser(1, "hi") }, blocking = { sendMessageToUser(1, "hi") }, async = { sendMessageToUser(1, "hi") },
        ),
        Case(
            "sendMessageToUser(body)", SENT, "POST", "/messages", mapOf("user_id" to "1"),
            body = """{"text":"hi"}""",
            kotlin = { sendMessageToUser(1, HI) }, blocking = { sendMessageToUser(1, HI) }, async = { sendMessageToUser(1, HI) },
        ),
        Case(
            "editMessage", SUCCESS, "PUT", "/messages", mapOf("message_id" to "mid.1"),
            body = """{"text":"hi"}""",
            kotlin = { editMessage("mid.1", HI) }, blocking = { editMessage("mid.1", HI) }, async = { editMessage("mid.1", HI) },
        ),
        Case(
            "deleteMessage", SUCCESS, "DELETE", "/messages", mapOf("message_id" to "mid.1"),
            kotlin = { deleteMessage("mid.1") }, blocking = { deleteMessage("mid.1") }, async = { deleteMessage("mid.1") },
        ),
        Case(
            "getMessageById", messageJson(), "GET", "/messages/mid.1", check = ::assertMessage,
            kotlin = { getMessageById("mid.1") }, blocking = { getMessageById("mid.1") }, async = { getMessageById("mid.1") },
        ),
        Case(
            "getVideoAttachmentDetails",
            """{"token":"vtok","width":640,"height":360,"duration":10,"urls":{"mp4_360":"https://v/360"}}""",
            "GET", "/videos/vtok",
            check = {
                it as VideoAttachmentDetails
                assertEquals(640, it.width)
                assertEquals("https://v/360", it.urls?.mp4p360)
            },
            kotlin = { getVideoAttachmentDetails("vtok") },
            blocking = { getVideoAttachmentDetails("vtok") },
            async = { getVideoAttachmentDetails("vtok") },
        ),
        Case(
            "answerOnCallback(answer)", SUCCESS, "POST", "/answers",
            mapOf("callback_id" to "cb", "disable_link_preview" to "false"),
            body = """{"message":{"text":"new"},"notification":"n"}""",
            kotlin = { answerOnCallback("cb", ANSWER, false) },
            blocking = { answerOnCallback("cb", ANSWER, false) },
            async = { answerOnCallback("cb", ANSWER, false) },
        ),
        Case(
            "answerOnCallback(notification)", SUCCESS, "POST", "/answers", mapOf("callback_id" to "cb"),
            body = """{"notification":"n"}""",
            kotlin = { answerOnCallback("cb", "n") }, blocking = { answerOnCallback("cb", "n") }, async = { answerOnCallback("cb", "n") },
        ),
        Case(
            "getComments", """{"messages":[$COMMENT]}""", "GET", "/messages/mid.1/comments",
            mapOf("comment_ids" to "c1,c2", "before" to "9", "after" to "1", "count" to "2"),
            check = { assertEquals("c1", ((it as List<*>).single() as CommentMessage).body.mid) },
            kotlin = { getComments("mid.1", listOf("c1", "c2"), 9, 1, 2) },
            blocking = { getComments("mid.1", listOf("c1", "c2"), 9, 1, 2) },
            async = { getComments("mid.1", listOf("c1", "c2"), 9, 1, 2) },
        ),
        Case(
            "sendComment", """{"message":$COMMENT}""", "POST", "/messages/mid.1/comments",
            mapOf("disable_link_preview" to "true"),
            body = """{"text":"<b>hi</b>","format":"html"}""",
            check = { assertEquals("c1", (it as CommentMessage).body.mid) },
            kotlin = { sendComment("mid.1", COMMENT_BODY, true) },
            blocking = { sendComment("mid.1", COMMENT_BODY, true) },
            async = { sendComment("mid.1", COMMENT_BODY, true) },
        ),
        Case(
            "editComment", SUCCESS, "PUT", "/messages/mid.1/comments", mapOf("comment_id" to "c1"),
            body = """{"text":"<b>hi</b>","format":"html"}""",
            kotlin = { editComment("mid.1", "c1", COMMENT_BODY) },
            blocking = { editComment("mid.1", "c1", COMMENT_BODY) },
            async = { editComment("mid.1", "c1", COMMENT_BODY) },
        ),
        Case(
            "deleteComment", SUCCESS, "DELETE", "/messages/mid.1/comments", mapOf("comment_id" to "c1"),
            kotlin = { deleteComment("mid.1", "c1") },
            blocking = { deleteComment("mid.1", "c1") },
            async = { deleteComment("mid.1", "c1") },
        ),
        Case(
            "getCommentById", COMMENT, "GET", "/messages/mid.1/comments/c1",
            check = { assertEquals("c1", (it as CommentMessage).body.mid) },
            kotlin = { getCommentById("mid.1", "c1") },
            blocking = { getCommentById("mid.1", "c1") },
            async = { getCommentById("mid.1", "c1") },
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

        val result = call()

        case.check(result)
        val request = server.take()
        assertEquals(case.method, request.method, "HTTP method")
        assertEquals(case.path, request.url.encodedPath, "path")
        val query = request.url.queryParameterNames.associateWith { request.url.queryParameter(it)!! }
        assertEquals(case.query, query, "query")
        if (case.body == null) {
            assertTrue(request.bodyText.isEmpty(), "unexpected body: ${request.bodyText}")
        } else {
            assertEquals(mapper.readTree(case.body), mapper.readTree(request.bodyText), "body")
        }
        assertEquals(TOKEN, request.headers["Authorization"])
    }

    private companion object {
        const val BOT = """{"user_id":1,"first_name":"Bot","is_bot":true}"""
        const val CHAT = """{"chat_id":-5,"type":"chat","status":"active"}"""
        const val MEMBER = """{"user_id":2,"first_name":"Ann","is_admin":true}"""
        const val MEMBERS = """{"members":[$MEMBER],"marker":7}"""
        const val COMMENT =
            """{"recipient":{"chat_id":-5,"chat_type":"channel"},"timestamp":1,"body":{"mid":"c1","seq":1,"text":"c"}}"""
        const val HOOK = "https://bot.example/hook"
        val SENT = """{"message":${messageJson()}}"""

        val COMMANDS = listOf(BotCommand("start", "Start"))
        val PATCH = ChatPatch(title = "New", notify = false)
        val ADMINS = listOf(ChatAdmin(2, linkedSetOf(ChatAdminPermission.WRITE, ChatAdminPermission.PIN_MESSAGE), "Boss"))
        val TYPES = listOf(UpdateType.MESSAGE_CREATED, UpdateType.BOT_STARTED)
        val HI = NewMessageBody(text = "hi")
        val ANSWER = CallbackAnswer(message = NewMessageBody(text = "new"), notification = "n")
        val COMMENT_BODY = NewCommentBody(text = "<b>hi</b>", format = TextFormat.HTML)

        fun assertSuccess(result: Any?) = assertTrue((result as SimpleQueryResult).success)
        fun assertMessage(result: Any?) = assertEquals("mid.1", (result as Message).body?.mid)
    }
}
