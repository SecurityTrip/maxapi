package ru.securitytrip.maxapi.spring

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import org.springframework.http.HttpStatus
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
internal fun reactiveWebhookEndpoint(bot: MaxBot, webhook: MaxBotProperties.Webhook): RouterFunction<ServerResponse> {
    val handler = bot.webhookHandler(webhook.secret)
    return RouterFunctions.route()
        .POST(webhook.path) { request -> handle(handler, request, webhook.async).flatMap { ServerResponse.status(it).build() } }
        .build()
}

private fun handle(handler: WebhookHandler, request: ServerRequest, async: Boolean): Mono<HttpStatus> {
    val secret = request.headers().firstHeader(WebhookHandler.SECRET_HEADER)
    return request.bodyToMono(ByteArray::class.java).defaultIfEmpty(ByteArray(0)).flatMap { body ->
        if (async) {
            Mono.just(statusOf { handler.handleAsync(body, secret) })
        } else {
            Mono.fromFuture(reactiveScope.future { statusOf { handler.handle(body, secret) } }, true)
        }
    }
}

/** Where reactive endpoints run handlers; a failed update is reported by the bot and never cancels the others. */
private val reactiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("max-webhook"))
