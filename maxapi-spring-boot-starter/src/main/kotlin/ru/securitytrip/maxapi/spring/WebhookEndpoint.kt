package ru.securitytrip.maxapi.spring

import org.springframework.http.HttpStatus
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.WebhookHandler
import ru.securitytrip.maxapi.client.MaxException

/**
 * `POST <path>` for Max webhook deliveries: 200 when the update was accepted, 401 on a wrong
 * secret, 400 on a body that is not an update, 503 when the bot is closed (Max redelivers later).
 */
internal fun webhookEndpoint(bot: MaxBot, webhook: MaxBotProperties.Webhook): RouterFunction<ServerResponse> {
    val handler = bot.webhookHandler(webhook.secret)
    return RouterFunctions.route()
        .POST(webhook.path) { request -> ServerResponse.status(handle(handler, request, webhook.async)).build() }
        .build()
}

private fun handle(handler: WebhookHandler, request: ServerRequest, async: Boolean): HttpStatus {
    val body = request.body(ByteArray::class.java)
    val secret = request.headers().firstHeader(WebhookHandler.SECRET_HEADER)
    return statusOf { if (async) handler.handleAsync(body, secret) else handler.handleBlocking(body, secret) }
}

/** The status for the result of a [WebhookHandler] call. */
internal inline fun statusOf(accept: () -> Boolean): HttpStatus = try {
    if (accept()) HttpStatus.OK else HttpStatus.UNAUTHORIZED
} catch (_: MaxException) {
    HttpStatus.BAD_REQUEST
} catch (_: IllegalStateException) {
    HttpStatus.SERVICE_UNAVAILABLE
}
