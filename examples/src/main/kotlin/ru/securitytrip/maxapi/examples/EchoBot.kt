package ru.securitytrip.maxapi.examples

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.dsl.message
import ru.securitytrip.maxapi.model.user.BotCommand

/**
 * Echo bot in Kotlin: commands, an inline keyboard, callback answers and long polling.
 *
 * Run: `MAX_BOT_TOKEN=... ./gradlew :examples:run`
 */
fun main(): Unit = runBlocking {
    val token = System.getenv("MAX_BOT_TOKEN") ?: error("Set the MAX_BOT_TOKEN environment variable")

    MaxBot(token).use { bot ->
        val me = bot.client.getMyInfo()
        println("Started as ${me.firstName} (@${me.username})")
        // Known username: commands addressed to this bot (/start@name) need no extra request.
        bot.username = me.username
        bot.client.editMyCommands(listOf(BotCommand("start", "Start"), BotCommand("help", "Help")))

        bot.onBotStarted {
            reply("Hi, ${user.firstName}! Send /start")
        }

        // Commands go before the catch-all onMessage: the first matching handler wins.
        bot.onCommand("start") {
            reply {
                text("Pick an option:")
                keyboard {
                    row {
                        callback("👍 Like", "like")
                        callback("👎 Dislike", "dislike")
                    }
                    row { link("API docs", "https://dev.max.ru/docs-api") }
                }
            }
        }

        bot.onCommand("help") {
            reply(message { markdown("**Echo bot**: I repeat everything you write. Commands: `/start`, `/help`") })
        }

        // The web client of Max does not show notifications, so the answer also changes the message.
        bot.onCallback("like") { editMessage(message { text("Thanks for the like! 👍") }, notification = "Thanks!") }

        bot.onCallback("dislike") {
            editMessage(message { text("Sorry to hear that 😔") }, notification = "Noted")
        }

        bot.onMessage {
            typing()
            replyToMessage(text ?: "I only understand text")
        }

        bot.onError { error, update -> System.err.println("Error on ${update?.updateType}: $error") }

        bot.startPolling()
    }
}
