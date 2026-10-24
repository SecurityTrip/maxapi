package ru.securitytrip.maxapi.examples

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.CallbackData
import ru.securitytrip.maxapi.bot.Filters
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.Router
import ru.securitytrip.maxapi.bot.and
import ru.securitytrip.maxapi.dsl.message

/**
 * Questionnaire bot: a dialog of several steps kept in the dialog state, a router per feature,
 * middleware, typed callback payloads and the command menu.
 *
 * Run: `MAX_BOT_TOKEN=... ./gradlew :examples:run -PmainClass=ru.securitytrip.maxapi.examples.QuestionnaireBotKt`
 */
fun main(): Unit = runBlocking {
    val token = System.getenv("MAX_BOT_TOKEN") ?: error("Set the MAX_BOT_TOKEN environment variable")
    val rating = CallbackData("rate", "stars")

    val questionnaire = Router()
        .onCommand("survey", "Take the survey") {
            setState("name")
            reply("What is your name?")
        }
        .onCommand("cancel", "Cancel the survey") {
            clearState()
            reply("Cancelled")
        }
        .onMessage(Filters.state("name") and Filters.hasText()) {
            setState("rating", mapOf("name" to text!!))
            reply {
                text("Nice to meet you, $text! How do you like the bot?")
                keyboard { row { (1..5).forEach { callback("$it ⭐", rating.pack(it)) } } }
            }
        }
        .onMessage(Filters.state("name", "rating")) { reply("Please answer the question above, or send /cancel") }
        .onCallback(rating.filter() and Filters.state("rating")) {
            val stars = data(rating).getValue("stars")
            val name = stateData["name"]
            clearState()
            editMessage(message { text("Thanks, $name: $stars ⭐") }, notification = "Saved")
        }

    MaxBot(token).use { bot ->
        bot.username = bot.client.getMyInfo().username

        // Outermost middleware: timing of every handled update.
        bot.use { ctx, next ->
            val started = System.nanoTime()
            try {
                next()
            } finally {
                println("${ctx.update.updateType} handled in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        }

        bot.onBotStarted { reply("Hi! Send /survey") }
        bot.include(questionnaire)
        bot.onMessage { reply("Send /survey to start") }

        bot.syncCommands()
        bot.startPolling()
    }
}
