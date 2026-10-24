package ru.securitytrip.maxapi.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.WebhookHandler
import ru.securitytrip.maxapi.client.MaxException

/**
 * Accepts Max webhook deliveries at `POST path` and passes them to [bot]:
 *
 * ```kotlin
 * embeddedServer(Netty, port = 8080) {
 *     routing { maxWebhook("/max/webhook", bot, secret = System.getenv("MAX_WEBHOOK_SECRET")) }
 * }.start(wait = true)
 * ```
 *
 * Responds with 200 when the update was accepted, 401 on a wrong secret, 400 on a body that is not
 * an update, and 503 when the bot is closed, so that Max redelivers later.
 *
 * @param secret the secret passed to `setWebhook`; `null` disables the check.
 * @param async `true` responds right after parsing the update and runs the handler in the background;
 *   `false` responds after the handler completes.
 */
public fun Route.maxWebhook(path: String, bot: MaxBot, secret: String? = null, async: Boolean = false): Route =
    maxWebhook(path, bot.webhookHandler(secret), async)

/** Same as the other `maxWebhook`, with a [WebhookHandler] you created yourself. */
public fun Route.maxWebhook(path: String, handler: WebhookHandler, async: Boolean = false): Route = post(path) {
    val body = call.receive<ByteArray>()
    val secret = call.request.headers[WebhookHandler.SECRET_HEADER]
    val status = try {
        val accepted = if (async) handler.handleAsync(body, secret) else handler.handle(body, secret)
        if (accepted) HttpStatusCode.OK else HttpStatusCode.Unauthorized
    } catch (_: MaxException) {
        HttpStatusCode.BadRequest
    } catch (_: IllegalStateException) {
        HttpStatusCode.ServiceUnavailable
    }
    call.respond(status)
}
