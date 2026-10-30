package ru.securitytrip.maxapi.examples

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.Scene
import ru.securitytrip.maxapi.bot.Translations
import ru.securitytrip.maxapi.bot.UpdateContext
import ru.securitytrip.maxapi.bot.scene

/**
 * Questionnaire bot: a scene of three steps with checks, "Back" and "Cancel", texts in Russian and
 * English chosen by the user's locale, middleware and the command menu.
 *
 * Run: `MAX_BOT_TOKEN=... ./gradlew :examples:run -PmainClass=ru.securitytrip.maxapi.examples.QuestionnaireBotKt`
 */
fun main(): Unit = runBlocking {
    val token = System.getenv("MAX_BOT_TOKEN") ?: error("Set the MAX_BOT_TOKEN environment variable")
    MaxBot(token).use { bot ->
        bot.username = bot.client.getMyInfo().username
        configureQuestionnaire(bot)
        bot.syncCommands()
        bot.startPolling()
    }
}

/** Texts of the bot: Russian, also for users of other languages, and English. */
val questionnaireTexts: Translations = Translations.of(
    fallback = "ru",
    texts = mapOf(
        "ru" to mapOf(
            "start" to "Привет! Пройдите короткий опрос.",
            "survey.button" to "Пройти опрос",
            "name" to "Как вас зовут?",
            "age" to "Сколько вам лет, {0}?",
            "age.invalid" to "Отправьте число от 1 до 120.",
            "rating" to "Как вам бот?",
            "back" to "◀ Назад",
            "cancel" to "Отмена",
            "years.one" to "{0} год",
            "years.few" to "{0} года",
            "years.many" to "{0} лет",
            "thanks" to "Спасибо, {0}! Вам {1}, оценка бота: {2} ⭐",
            "cancelled" to "Опрос отменён. Отправьте /survey, чтобы начать заново.",
        ),
        "en" to mapOf(
            "start" to "Hi! Take a short survey.",
            "survey.button" to "Take the survey",
            "name" to "What is your name?",
            "age" to "How old are you, {0}?",
            "age.invalid" to "Send a number from 1 to 120.",
            "rating" to "How do you like the bot?",
            "back" to "◀ Back",
            "cancel" to "Cancel",
            "years.one" to "{0} year",
            "years.other" to "{0} years",
            "thanks" to "Thanks, {0}! You are {1} old and rate the bot {2} ⭐",
            "cancelled" to "The survey is cancelled. Send /survey to start again.",
        ),
    ),
)

/** The survey: a name, an age from 1 to 120 and a rating with buttons. */
fun questionnaire(texts: Translations = questionnaireTexts): Scene = scene("survey") {
    enterOnCommand("survey", "Take the survey")
    enterOnCallback("survey")
    step("name") {
        prompt { reply(texts.text(this, "name")) }
        validate { it.isNotBlank() && it.length <= 50 }
    }
    step("age") {
        prompt {
            reply {
                text(texts.text(locale, "age", answers["name"]))
                keyboard { row(backButton(texts.text(locale, "back")), cancelButton(texts.text(locale, "cancel"))) }
            }
        }
        validate { it.toIntOrNull() in 1..120 }
        onInvalid { reply(texts.text(this, "age.invalid")) }
    }
    step("rating") {
        prompt {
            reply {
                text(texts.text(locale, "rating"))
                keyboard {
                    row((1..5).map { option("$it ⭐", "$it") })
                    row(backButton(texts.text(locale, "back")), cancelButton(texts.text(locale, "cancel")))
                }
            }
        }
    }
    onFinish {
        val years = texts.plural(this, "years", answers.getValue("age").toLong())
        reply(texts.text(this, "thanks", answers["name"], years, answers["rating"]))
    }
    onCancel { reply(texts.text(this, "cancelled")) }
}

/** Registers the handlers of the questionnaire bot. */
fun configureQuestionnaire(bot: MaxBot, texts: Translations = questionnaireTexts) {
    // Outermost middleware: timing of every handled update.
    bot.use { ctx, next ->
        val started = System.nanoTime()
        try {
            next()
        } finally {
            println("${ctx.update.updateType} handled in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }
    val greeting: suspend UpdateContext<*>.() -> Unit = {
        reply {
            text(texts.text(locale, "start"))
            keyboard { row { callback(texts.text(locale, "survey.button"), "survey") } }
        }
    }
    bot.onBotStarted { greeting() }
    // The scene goes before the catch-all handler, which would take the answers otherwise.
    bot.include(questionnaire(texts))
    bot.onMessage { greeting() }
}
