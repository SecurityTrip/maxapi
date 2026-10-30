package ru.securitytrip.maxapi.examples

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.model.attachment.InlineKeyboardAttachmentRequest
import ru.securitytrip.maxapi.model.keyboard.CallbackButton
import ru.securitytrip.maxapi.test.BotTester
import ru.securitytrip.maxapi.test.SentMessage
import ru.securitytrip.maxapi.test.TestUpdates
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The questionnaire as a user goes through it, without a network. */
class QuestionnaireBotTest {
    private val tester = BotTester().also { configureQuestionnaire(it.bot) }

    @AfterTest
    fun tearDown() {
        tester.close()
    }

    /** Payloads of the buttons of a sent message, by their text. */
    private fun SentMessage.buttons(): Map<String, String> =
        body.attachments.orEmpty().filterIsInstance<InlineKeyboardAttachmentRequest>()
            .flatMap { it.payload.buttons.flatten() }.filterIsInstance<CallbackButton>().associate { it.text to it.payload }

    @Test
    fun `the survey in Russian, with an invalid answer and a step back`(): Unit = runBlocking {
        tester.startBot()
        tester.pressButton(tester.sentMessages.last().buttons().getValue("Пройти опрос"))
        tester.sendMessage("Анна")
        tester.sendMessage("сто")
        tester.pressButton(tester.sentMessages.last().buttons().getValue("◀ Назад"))
        tester.sendMessage("Аня")
        tester.sendMessage("22")
        tester.pressButton(tester.sentMessages.last().buttons().getValue("5 ⭐"))

        assertEquals(
            listOf(
                "Привет! Пройдите короткий опрос.",
                "Как вас зовут?",
                "Сколько вам лет, Анна?",
                "Отправьте число от 1 до 120.",
                "Сколько вам лет, Анна?",
                "Как вас зовут?",
                "Сколько вам лет, Аня?",
                "Как вам бот?",
                "Спасибо, Аня! Вам 22 года, оценка бота: 5 ⭐",
            ),
            tester.sentMessages.map { it.text },
        )
    }

    @Test
    fun `the survey in English and cancelled`(): Unit = runBlocking {
        fun send(text: String) = runBlocking { tester.send(TestUpdates.message(text).copy(userLocale = "en-US")) }
        send("/survey")
        send("Ann")
        send("/cancel")
        send("hello")
        assertEquals(
            listOf(
                "What is your name?",
                "How old are you, Ann?",
                "The survey is cancelled. Send /survey to start again.",
                "Hi! Take a short survey.",
            ),
            tester.sentMessages.map { it.text },
        )
        assertEquals("1 year", questionnaireTexts.plural("en", "years", 1))
        assertEquals(listOf("survey" to "Take the survey"), tester.bot.commands().map { it.name to it.description })
    }
}
