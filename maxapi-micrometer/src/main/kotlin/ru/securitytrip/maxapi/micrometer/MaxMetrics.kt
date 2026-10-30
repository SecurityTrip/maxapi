package ru.securitytrip.maxapi.micrometer

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.events.RequestEvent
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.events.WebhookResubscribeEvent

/**
 * Records maxapi events as Micrometer meters:
 *
 * | Meter                             | Kind    | Tags                                                        |
 * |-----------------------------------|---------|-------------------------------------------------------------|
 * | `max.client.requests`             | timer   | `method`, `path` (like `/chats/{id}`), `status`, `outcome`, `retry` |
 * | `max.bot.updates`                 | timer   | `type` (update type), `handled`, `outcome`, `exception`     |
 * | `max.bot.webhook.resubscriptions` | counter | `outcome`, `exception`                                      |
 *
 * `outcome` is `SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR` or `NETWORK_ERROR` for requests, and
 * `SUCCESS` or `ERROR` for updates and webhook subscriptions renewed after Max dropped them. `status` is `NONE` after a network error, `exception` is the
 * simple class name of the error or `none`.
 *
 * ```kotlin
 * val metrics = MaxMetrics(registry)
 * val client = MaxClient(MaxClientConfig(token, eventListener = metrics))
 * val bot = MaxBot(client, BotConfig(eventListener = metrics))
 * ```
 *
 * The Spring Boot starter creates it automatically when Micrometer and a `MeterRegistry` are present.
 */
public class MaxMetrics(private val registry: MeterRegistry) : MaxEventListener {
    override fun onRequest(event: RequestEvent) {
        val status = event.status
        val outcome = when {
            status == null -> "NETWORK_ERROR"
            status < 400 -> "SUCCESS"
            status < 500 -> "CLIENT_ERROR"
            else -> "SERVER_ERROR"
        }
        Timer.builder(REQUESTS)
            .description("Requests to Max Bot API, one per attempt")
            .tag("method", event.method)
            .tag("path", event.path)
            .tag("status", if (status == null) "NONE" else status.toString())
            .tag("outcome", outcome)
            .tag("retry", (event.attempt > 0).toString())
            .register(registry)
            .record(event.duration)
    }

    override fun onUpdate(event: UpdateEvent) {
        val error = event.error
        Timer.builder(UPDATES)
            .description("Updates dispatched by the bot")
            .tag("type", event.update.updateType)
            .tag("handled", event.handled.toString())
            .tag("outcome", if (error == null) "SUCCESS" else "ERROR")
            .tag("exception", if (error == null) "none" else error.javaClass.simpleName)
            .register(registry)
            .record(event.duration)
    }

    override fun onWebhookResubscribe(event: WebhookResubscribeEvent) {
        val error = event.error
        Counter.builder(RESUBSCRIPTIONS)
            .description("Webhook subscriptions renewed after Max dropped them")
            .tag("outcome", if (error == null) "SUCCESS" else "ERROR")
            .tag("exception", if (error == null) "none" else error.javaClass.simpleName)
            .register(registry)
            .increment()
    }

    public companion object {
        /** Name of the request timer. */
        public const val REQUESTS: String = "max.client.requests"

        /** Name of the update timer. */
        public const val UPDATES: String = "max.bot.updates"

        /** Name of the counter of renewed webhook subscriptions. */
        public const val RESUBSCRIPTIONS: String = "max.bot.webhook.resubscriptions"
    }
}
