package ru.securitytrip.maxapi.test

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.Filters
import ru.securitytrip.maxapi.client.NotFoundException
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.message.CallbackAnswer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotTesterTest {
    private val tester = BotTester()

    @AfterTest
    fun tearDown() {
        tester.close()
    }

    @Test
    fun `records sent messages`(): Unit = runBlocking {
        tester.bot.onCommand("start") {
            val sent = reply {
                text("Pick one")
                keyboard { row { callback("A", "a") } }
            }
            assertEquals("Pick one", sent.body?.text)
            assertEquals(100L, sent.recipient.chatId)
        }
        tester.sendMessage("/start")
        val message = tester.sentMessages.single()
        assertEquals(TestUpdates.CHAT_ID, message.chatId)
        assertNull(message.userId)
        assertEquals("Pick one", message.text)
        assertEquals(1, message.body.attachments?.size)

        val call = tester.calls.single()
        assertEquals("POST", call.method)
        assertEquals("/messages", call.path)
        assertEquals(mapOf("chat_id" to "100"), call.query)
        assertEquals("Pick one", call.json?.get("text")?.asText())
        assertTrue(call.toString().startsWith("POST /messages{chat_id=100} {"))
    }

    @Test
    fun `messages to users and messages without text`(): Unit = runBlocking {
        tester.bot.onMessage {
            client.sendMessageToUser(7, message { markdown("**x**") })
            val sent = client.sendMessageToUser(8, message { keyboard { row { callback("A", "a") } } })
            assertNull(sent.body?.text)
            assertEquals(8L, sent.recipient.userId)
        }
        tester.sendMessage("hi")
        assertEquals(listOf(7L, 8L), tester.sentMessages.map { it.userId })
        assertEquals(listOf("**x**", null), tester.sentMessages.map { it.text })
    }

    @Test
    fun `records callback answers`(): Unit = runBlocking {
        tester.bot.onCallback("like") { reply("Noted"); answer("Thanks!") }
        tester.bot.onCallback("edit") { editMessage(message { text("Edited") }) }
        tester.pressButton("like", userId = 5)
        tester.pressButton("edit")
        val answers = tester.callbackAnswers
        assertEquals(CallbackAnswer(notification = "Thanks!"), answers[0].answer)
        assertTrue(answers[0].callbackId.startsWith("cb."))
        assertEquals("Edited", answers[1].answer.message?.text)
    }

    @Test
    fun `deep links, chat types and state`(): Unit = runBlocking {
        tester.bot.onBotStarted { reply("ref=${payload}") }
        tester.bot.onMessage(Filters.chatType(ChatType.CHAT)) { setState("group") }
        tester.bot.onMessage(Filters.state("group")) { reply("in state") }
        tester.startBot("promo")
        tester.startBot()
        tester.sendMessage("x", chatType = ChatType.CHAT, chatId = 5)
        tester.sendMessage("y", chatId = 5)
        assertEquals(listOf("ref=promo", "ref=null", "in state"), tester.sentMessages.map { it.text })
    }

    @Test
    fun `handler exceptions fail the send`(): Unit = runBlocking {
        tester.bot.onMessage { error("broken") }
        val error = assertFailsWith<IllegalStateException> { tester.sendMessage("x") }
        assertEquals("broken", error.message)
    }

    @Test
    fun `stubs answer calls`(): Unit = runBlocking {
        tester.stub("get", "/chats/42", """{"chat_id":42,"type":"chat","status":"active","title":"Team","last_event_time":1,"participants_count":3,"is_public":false}""")
        tester.stub("GET", "/chats/43", """{"code":"not.found","message":"Chat not found"}""", status = 404)
        tester.bot.onMessage {
            reply("Title: ${client.getChat(42).title}")
            assertFailsWith<NotFoundException> { client.getChat(43) }
        }
        tester.sendMessage("x")
        assertEquals("Title: Team", tester.sentMessages.single().text)
    }

    @Test
    fun `default responses`(): Unit = runBlocking {
        tester.bot.onMessage {
            assertEquals(BotTester.BOT_USERNAME, client.getMyInfo().username)
            assertTrue(client.deleteMessage("m").success)
        }
        tester.sendMessage("x")
        assertEquals(listOf("GET /me", "DELETE /messages"), tester.calls.map { "${it.method} ${it.path}" })
        assertNull(tester.calls[0].json)
        assertEquals("GET /me{}", tester.calls[0].toString())
        tester.clearCalls()
        assertEquals(emptyList(), tester.calls)
    }

    @Test
    fun `addressed commands reach the bot without a request`(): Unit = runBlocking {
        tester.bot.onCommand("start") { reply("ok") }
        tester.sendMessage("/start@${BotTester.BOT_USERNAME}")
        tester.sendMessage("/start@other_bot")
        assertEquals(listOf("POST /messages"), tester.calls.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `blocking methods for Java`() {
        val tester = BotTester(BotConfig())
        tester.bot.onMessage { reply("echo $text") }
        tester.bot.onCallback { answer(payload!!) }
        tester.bot.onBotStarted { reply("started") }
        tester.sendMessageBlocking("a")
        tester.sendBlocking(TestUpdates.message("b", mid = "x"))
        tester.pressButtonBlocking("p")
        tester.startBotBlocking()
        assertEquals(listOf("echo a", "echo b", "started"), tester.sentMessages.map { it.text })
        assertEquals("p", tester.callbackAnswers.single().answer.notification)
        tester.close()
    }

    @Test
    fun `test updates`() {
        val message = TestUpdates.message(null, chatId = 1, userId = 2, chatType = ChatType.CHANNEL, mid = "m")
        assertNull(message.message.body?.text)
        assertEquals(ChatType.CHANNEL, message.message.recipient.chatType)
        assertEquals("User 2", message.user?.firstName)
        val callback = TestUpdates.callback("p", chatId = 3, userId = 4, callbackId = "c")
        assertEquals(3L, callback.chatId)
        assertEquals(4L, callback.callback.user.userId)
        val started = TestUpdates.botStarted("ref", userId = 5, chatId = 6)
        assertEquals("ref", started.payload)
        assertEquals(6L, started.chatId)
        assertEquals(TestUpdates.USER_ID, TestUpdates.user().userId)
    }
}
