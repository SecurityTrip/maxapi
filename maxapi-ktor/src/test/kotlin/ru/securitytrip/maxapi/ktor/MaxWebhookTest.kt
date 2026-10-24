package ru.securitytrip.maxapi.ktor

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.WebhookHandler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class MaxWebhookTest {
    private val log = CopyOnWriteArrayList<String>()

    private val update = """{"update_type":"message_created","timestamp":1,"message":{"sender":{"user_id":1},
        "recipient":{"chat_id":5,"chat_type":"dialog"},"timestamp":1,"body":{"mid":"m","seq":1,"text":"hi"}}}"""

    private fun bot(): MaxBot = MaxBot("token").onMessage { log += "handled $text" }

    @Test
    fun `delivers updates and checks the secret`(): Unit = testApplication {
        val bot = bot()
        routing { maxWebhook("/max/webhook", bot, secret = "s3cret") }

        val ok = client.post("/max/webhook") {
            header(WebhookHandler.SECRET_HEADER, "s3cret")
            setBody(update)
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(listOf("handled hi"), log)

        val wrong = client.post("/max/webhook") {
            header(WebhookHandler.SECRET_HEADER, "wrong")
            setBody(update)
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val invalid = client.post("/max/webhook") {
            header(WebhookHandler.SECRET_HEADER, "s3cret")
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(1, log.size)
        bot.close()
    }

    @Test
    fun `async delivery and a closed bot`(): Unit = testApplication {
        val bot = bot()
        routing { maxWebhook("/hook", bot.webhookHandler(), async = true) }

        assertEquals(HttpStatusCode.OK, client.post("/hook") { setBody(update) }.status)
        repeat(500) { if (log.isEmpty()) delay(10) }
        assertEquals(listOf("handled hi"), log)

        bot.close()
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/hook") { setBody(update) }.status)
    }
}
