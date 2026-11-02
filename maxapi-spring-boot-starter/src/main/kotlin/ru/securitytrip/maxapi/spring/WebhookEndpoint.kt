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
 * secret, 413 on a body over [MaxBotProperties.Webhook.maxBodySize], 400 on a body that is not an
 * update, 503 when the bot is closed or the chat has too many updates waiting (Max redelivers later).
 * The secret is checked before the body is read.
 */
internal fun webhookEndpoint(bot: MaxBot, webhook: MaxBotProperties.Webhook, secret: String): RouterFunction<ServerResponse> {
    val handler = bot.webhookHandler(secret)
    val limit = bodyLimit(webhook)
    return RouterFunctions.route()
        .POST(webhook.path) { request -> ServerResponse.status(handle(handler, request, webhook.async, limit)).build() }
        .build()
}

private fun handle(handler: WebhookHandler, request: ServerRequest, async: Boolean, limit: Int): HttpStatus {
    val secret = request.headers().firstHeader(WebhookHandler.SECRET_HEADER)
    if (!handler.verifySecret(secret)) return HttpStatus.UNAUTHORIZED
    val servlet = request.servletRequest()
    if (servlet.contentLengthLong > limit) return HttpStatus.CONTENT_TOO_LARGE
    val body = servlet.inputStream.readNBytes(limit + 1)
    if (body.size > limit) return HttpStatus.CONTENT_TOO_LARGE
    return statusOf { if (async) handler.handleAsync(body, secret) else handler.handleBlocking(body, secret) }
}

/** [MaxBotProperties.Webhook.maxBodySize] in bytes. */
internal fun bodyLimit(webhook: MaxBotProperties.Webhook): Int {
    val bytes = webhook.maxBodySize.toBytes()
    require(bytes in 1 until Int.MAX_VALUE) { "max.bot.webhook.max-body-size must be positive and under 2 GB: ${webhook.maxBodySize}" }
    return bytes.toInt()
}

/** The status for a [WebhookHandler] call; the endpoints check the secret before, so the call accepts it. */
internal inline fun statusOf(handle: () -> Boolean): HttpStatus = try {
    handle()
    HttpStatus.OK
} catch (_: MaxException) {
    HttpStatus.BAD_REQUEST
} catch (_: IllegalStateException) {
    HttpStatus.SERVICE_UNAVAILABLE
}
