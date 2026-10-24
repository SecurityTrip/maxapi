package ru.securitytrip.maxapi.examples.ktor

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.ktor.maxWebhook

/**
 * Webhook bot on Ktor. Max delivers updates only to HTTPS on port 443, so run it behind a reverse
 * proxy that terminates TLS and forwards to port 8080.
 */
fun main() {
    val token = System.getenv("MAX_BOT_TOKEN") ?: error("Set the MAX_BOT_TOKEN environment variable")
    val url = System.getenv("MAX_WEBHOOK_URL") ?: error("Set MAX_WEBHOOK_URL, e.g. https://bot.example.com/max/webhook")
    val secret = System.getenv("MAX_WEBHOOK_SECRET") ?: "change-me-please"

    val bot = MaxBot(token)
    bot.onCommand("start") { reply("Hello from Ktor!") }
    bot.onMessage { replyToMessage(text ?: "I only understand text") }
    runBlocking { bot.setWebhook(url, secret) }

    embeddedServer(Netty, port = 8080) {
        routing { maxWebhook("/max/webhook", bot, secret) }
    }.start(wait = true)
}
