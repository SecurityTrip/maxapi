package ru.securitytrip.maxapi.micrometer

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.events.RequestEvent
import ru.securitytrip.maxapi.events.UpdateEvent

/**
 * Records maxapi events as Micrometer timers:
 *
 * | Timer                 | Tags                                                        |
 * |-----------------------|-------------------------------------------------------------|
 * | `max.client.requests` | `method`, `path` (like `/chats/{id}`), `status`, `outcome`, `retry` |
 * | `max.bot.updates`     | `type` (update type), `handled`, `outcome`, `exception`     |
 *
 * `outcome` is `SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR` or `NETWORK_ERROR` for requests, and
 * `SUCCESS` or `ERROR` for updates. `status` is `NONE` after a network error, `exception` is the
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

    public companion object {
        /** Name of the request timer. */
        public const val REQUESTS: String = "max.client.requests"

        /** Name of the update timer. */
        public const val UPDATES: String = "max.bot.updates"
    }
}
