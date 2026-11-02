package ru.securitytrip.maxapi.spring

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.bot.WebhookHandler

// Apart from WebhookEndpoint.kt, so that servlet applications never load the classes of WebFlux.

/**
 * The webhook endpoint of [webhookEndpoint] for Spring WebFlux, with the same answers. The update is
 * handled on `Dispatchers.Default`, never on the server's event loop; with `async = false` the response
 * waits for the handler without blocking a thread. A request the client abandons still finishes
 * handling, as the update may already be marked as seen.
 */
internal fun reactiveWebhookEndpoint(bot: MaxBot, webhook: MaxBotProperties.Webhook, secret: String): RouterFunction<ServerResponse> {
    val handler = bot.webhookHandler(secret)
    val limit = bodyLimit(webhook)
    return RouterFunctions.route()
        .POST(webhook.path) { request -> handle(handler, request, webhook.async, limit).flatMap { ServerResponse.status(it).build() } }
        .build()
}

private fun handle(handler: WebhookHandler, request: ServerRequest, async: Boolean, limit: Int): Mono<HttpStatus> {
    val secret = request.headers().firstHeader(WebhookHandler.SECRET_HEADER)
    if (!handler.verifySecret(secret)) return Mono.just(HttpStatus.UNAUTHORIZED)
    if (request.headers().contentLength().orElse(0) > limit) return Mono.just(HttpStatus.CONTENT_TOO_LARGE)
    return DataBufferUtils.join(request.body(BodyExtractors.toDataBuffers()), limit)
        .map { buffer -> ByteArray(buffer.readableByteCount()).also { buffer.read(it); DataBufferUtils.release(buffer) } }
        .defaultIfEmpty(ByteArray(0))
        .flatMap { body ->
            if (async) {
                Mono.just(statusOf { handler.handleAsync(body, secret) })
            } else {
                Mono.fromFuture(reactiveScope.future { statusOf { handler.handle(body, secret) } }, true)
            }
        }
        .onErrorReturn(DataBufferLimitException::class.java, HttpStatus.CONTENT_TOO_LARGE)
}

/** Where reactive endpoints run handlers; a failed update is reported by the bot and never cancels the others. */
private val reactiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("max-webhook"))
