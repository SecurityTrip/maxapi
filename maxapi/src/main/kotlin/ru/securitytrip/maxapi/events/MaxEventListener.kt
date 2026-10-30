package ru.securitytrip.maxapi.events

import ru.securitytrip.maxapi.model.update.Update
import java.time.Duration

/**
 * Receives events about API requests and handled updates, for metrics, tracing and logging.
 * Set it with `MaxClientConfig.eventListener` (requests) and `BotConfig.eventListener` (updates);
 * the `maxapi-micrometer` module has a ready-made implementation.
 *
 * Methods are called synchronously on the thread of the request or handler, so keep them fast.
 * Exceptions thrown by a listener are logged and otherwise ignored.
 */
@JvmDefaultWithoutCompatibility
public interface MaxEventListener {
    /** An HTTP attempt to the API completed: with a response, or with a network error. Retries are separate events. */
    public fun onRequest(event: RequestEvent) {}

    /** The bot finished dispatching an update. */
    public fun onUpdate(event: UpdateEvent) {}

    /**
     * The bot found its webhook subscription missing and subscribed again
     * (see `MaxBot.ensureWebhook`): Max drops a subscription after 8 hours without successful deliveries.
     */
    public fun onWebhookResubscribe(event: WebhookResubscribeEvent) {}

    public companion object {
        /** A listener that ignores all events. */
        @JvmField
        public val NONE: MaxEventListener = object : MaxEventListener {}

        /** A listener that passes every event to each of [listeners] in order. */
        @JvmStatic
        public fun composite(listeners: List<MaxEventListener>): MaxEventListener = when (listeners.size) {
            0 -> NONE
            1 -> listeners[0]
            else -> object : MaxEventListener {
                override fun onRequest(event: RequestEvent) = listeners.forEach { it.onRequest(event) }
                override fun onUpdate(event: UpdateEvent) = listeners.forEach { it.onUpdate(event) }
                override fun onWebhookResubscribe(event: WebhookResubscribeEvent) =
                    listeners.forEach { it.onWebhookResubscribe(event) }
            }
        }
    }
}

/**
 * One HTTP attempt.
 *
 * @property method HTTP method.
 * @property path path with identifiers replaced by `{id}`, like `/chats/{id}/members`, so that it
 *   can be used as a metric tag.
 * @property status HTTP status, or `null` if the attempt failed with a network error.
 * @property duration time from sending the request to reading the response.
 * @property attempt zero-based attempt number: `0` for the first try, `1` for the first retry.
 * @property error the network error, if any.
 */
public data class RequestEvent(
    val method: String,
    val path: String,
    val status: Int?,
    val duration: Duration,
    val attempt: Int,
    val error: Throwable?,
)

/**
 * One dispatched update.
 *
 * @property update the update.
 * @property handled whether a handler matched it; `false` also for updates dropped as duplicates.
 * @property duration time spent on the update, middleware and handler included.
 * @property error the exception of the handler (also passed to `onError`), if any.
 */
public data class UpdateEvent(
    val update: Update,
    val handled: Boolean,
    val duration: Duration,
    val error: Throwable?,
)

/**
 * A webhook subscription that was found missing and renewed.
 *
 * @property url the URL the bot subscribed to again.
 * @property error why subscribing again failed, or `null` if it succeeded.
 */
public data class WebhookResubscribeEvent(
    val url: String,
    val error: Throwable?,
)

private val log: System.Logger = System.getLogger("ru.securitytrip.maxapi.events")

/** Calls [block] with this listener, logging instead of propagating its exceptions. */
internal inline fun MaxEventListener.safely(block: MaxEventListener.() -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.log(System.Logger.Level.WARNING, "Event listener failed", e)
    }
}
