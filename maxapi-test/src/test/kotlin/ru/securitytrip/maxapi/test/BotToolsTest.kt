package ru.securitytrip.maxapi.test

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.BroadcastTarget
import ru.securitytrip.maxapi.bot.Broadcaster
import ru.securitytrip.maxapi.bot.Confirmation
import ru.securitytrip.maxapi.bot.InMemoryChatStore
import ru.securitytrip.maxapi.bot.Paginator
import ru.securitytrip.maxapi.bot.Translations
import ru.securitytrip.maxapi.bot.scene
import ru.securitytrip.maxapi.dsl.Buttons
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.chat.ChatType
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.model.message.NewMessageBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bot tools of 1.6 tested the way a bot author would: through [BotTester]. */
class BotToolsTest {
    private val chats = InMemoryChatStore()
    private val tester = BotTester(BotConfig(chatStore = chats))

    @AfterTest
    fun tearDown() {
        tester.close()
    }

    @Test
    fun `a scene runs through the tester`(): Unit = runBlocking {
        val survey = scene("survey") {
            enterOnCommand("survey")
            step("name") { prompt("Your name?") }
            step("rating") {
                prompt { reply { text("Rate us"); keyboard { row(option("1", "1"), option("5", "5")); row(cancelButton("Cancel")) } } }
            }
            onFinish { reply("Thanks, ${answers["name"]}: ${answers["rating"]}") }
        }
        tester.bot.include(survey)

        tester.sendMessage("/survey")
        tester.sendMessage("Ann")
        val keyboard = tester.sentMessages.last().body.attachments!!.single()
        val five = (keyboard as ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest).payload.buttons[0][1] as CallbackButton
        tester.pressButton(five.payload)
        assertEquals(listOf("Your name?", "Rate us", "Thanks, Ann: 5"), tester.sentMessages.map { it.text })
        assertEquals(listOf("✓"), tester.callbackAnswers.map { it.answer.notification })
    }

    @Test
    fun `the chat registry follows the events`(): Unit = runBlocking {
        tester.send(TestUpdates.botAdded(chatId = -10))
        tester.send(TestUpdates.botAdded(chatId = -20, isChannel = true))
        tester.startBot(userId = 7)
        tester.send(TestUpdates.chatTitleChanged("Team", chatId = -10))
        tester.send(TestUpdates.botRemoved(chatId = -20, isChannel = true))
        assertEquals(setOf(-10L, TestUpdates.CHAT_ID), tester.bot.chats().map { it.chatId }.toSet())
        assertEquals("Team", tester.bot.chats(ChatType.CHAT).single().title)

        tester.send(TestUpdates.botStopped(userId = 7))
        assertEquals(listOf(-10L), tester.bot.chats().map { it.chatId })
    }

    @Test
    fun `a broadcast through the tester client`(): Unit = runBlocking {
        tester.send(TestUpdates.botAdded(chatId = -10))
        tester.send(TestUpdates.botAdded(chatId = -11))
        val broadcaster = Broadcaster(tester.client)
        val report = broadcaster.send("hello", tester.bot.chats().map(BroadcastTarget::of), NewMessageBody(text = "Hello, chat!"))
        assertEquals(2, report.sent)
        assertEquals(setOf(-10L, -11L), tester.sentMessages.map { it.chatId }.toSet())

        tester.stub("POST", "/messages", """{"code":"chat.denied","message":"stopped"}""", status = 403)
        val blocked = broadcaster.send("blocked", BroadcastTarget.users(listOf(1L, 2L)), NewMessageBody(text = "x"))
        assertEquals(2, blocked.unavailable.size)
    }

    @Test
    fun `pages and confirmations through the tester`(): Unit = runBlocking {
        val catalog = Paginator("catalog", pageSize = 2)
        val items = listOf("Tea", "Coffee", "Juice")
        val clear = Confirmation("clear")
        fun page(number: Int) = message {
            text("Menu")
            attach(catalog.keyboard(items, number) { Buttons.callback(it, "item:$it") })
        }
        tester.bot.onCommand("menu") { reply(page(0)) }
        tester.bot.onCallback(catalog.filter()) { editMessage(page(catalog.page(this))) }
        tester.bot.onCommand("clear") { reply { text("Clear?"); attach(clear.keyboard()) } }
        tester.bot.onCallback(clear.yes()) { answer("Cleared") }

        tester.sendMessage("/menu")
        tester.pressButton(catalog.callbackData.pack(1, ""))
        val edited = tester.callbackAnswers.single().answer.message!!.attachments!!.single()
        val buttons = (edited as ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest).payload.buttons
        assertEquals(listOf(listOf("Juice"), listOf("◀", "2/2")), buttons.map { row -> row.map { it.text } })

        tester.sendMessage("/clear")
        tester.pressButton("clear:yes:")
        assertEquals("Cleared", tester.callbackAnswers.last().answer.notification)
    }

    @Test
    fun `texts follow the locale of the user`(): Unit = runBlocking {
        val texts = Translations.of("ru", mapOf("ru" to mapOf("hi" to "Привет"), "en" to mapOf("hi" to "Hi")))
        tester.bot.onMessage { reply(texts.text(this, "hi")) }
        tester.send(TestUpdates.message("x").copy(userLocale = "en-US"))
        tester.sendMessage("y")
        assertEquals(listOf("Hi", "Привет"), tester.sentMessages.map { it.text })
    }
}
