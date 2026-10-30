package ru.securitytrip.maxapi.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.extension.kotlin.asContextElement
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.events.MaxEventListener
import ru.securitytrip.maxapi.events.RequestEvent
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.events.WebhookResubscribeEvent
import ru.securitytrip.maxapi.model.update.Update
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Records maxapi events as OpenTelemetry spans, following the semantic conventions:
 *
 * | Span                         | Kind     | Attributes                                                                  |
 * |------------------------------|----------|-----------------------------------------------------------------------------|
 * | `GET /chats/{id}`            | CLIENT   | `http.request.method`, `url.template`, `http.response.status_code`, `server.address`, `server.port`, `http.request.resend_count`, `error.type` |
 * | `process message_created`    | CONSUMER | `messaging.system` (`max`), `messaging.operation.type` and `.name` (`process`), `max.update.type`, `max.chat.id`, `max.update.handled`, `error.type` |
 * | `max.webhook.resubscribe`    | INTERNAL | `url.full`, `error.type`                                                    |
 *
 * One request span is recorded per HTTP attempt, so a retried request has several, the retries with
 * `http.request.resend_count`. A span has the status `ERROR` after a network error, an HTTP status of
 * 400 or more, or a failed handler; `error.type` is then the status code or the class name of the error.
 *
 * The span of an update starts when the bot receives the update and is current in the coroutines of
 * its handler, so the requests the handler makes, and the spans of your own code in it, are its
 * children. Its parent is the span current where the update came from: the server span of a webhook
 * request handled with `handle`/`handleBlocking`, or none for long polling and `handleAsync`.
 *
 * ```kotlin
 * val tracing = MaxTracing(openTelemetry)
 * val client = MaxClient(MaxClientConfig(token, eventListener = tracing))
 * val bot = MaxBot(client, BotConfig(eventListener = tracing))
 * ```
 *
 * The Spring Boot starter creates it automatically when the application has an `OpenTelemetry` bean.
 *
 * @param openTelemetry where the spans go.
 * @param baseUrl base URL of the API the client uses, for `server.address` and `server.port`.
 */
public class MaxTracing @JvmOverloads constructor(
    openTelemetry: OpenTelemetry,
    baseUrl: String = MaxClientConfig.DEFAULT_BASE_URL,
) : MaxEventListener {
    private val tracer: Tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE)
    private val serverAddress: String?
    private val serverPort: Long?

    init {
        val uri = URI(baseUrl)
        serverAddress = uri.host
        serverPort = when {
            uri.port >= 0 -> uri.port.toLong()
            uri.scheme == "https" -> 443L
            uri.scheme == "http" -> 80L
            else -> null
        }
    }

    override fun onRequest(event: RequestEvent) {
        val end = epochNanos()
        val span = tracer.spanBuilder("${event.method} ${event.path}")
            .setSpanKind(SpanKind.CLIENT)
            .setStartTimestamp(end - event.duration.toNanos(), TimeUnit.NANOSECONDS)
            .setAttribute(HTTP_REQUEST_METHOD, event.method)
            .setAttribute(URL_TEMPLATE, event.path)
            .startSpan()
        serverAddress?.let { span.setAttribute(SERVER_ADDRESS, it) }
        serverPort?.let { span.setAttribute(SERVER_PORT, it) }
        if (event.attempt > 0) span.setAttribute(HTTP_REQUEST_RESEND_COUNT, event.attempt.toLong())
        val status = event.status
        if (status != null) span.setAttribute(HTTP_RESPONSE_STATUS_CODE, status.toLong())
        val error = event.error
        when {
            error != null -> fail(span, error)
            status != null && status >= 400 -> {
                span.setAttribute(ERROR_TYPE, status.toString())
                span.setStatus(StatusCode.ERROR)
            }
        }
        span.end(end, TimeUnit.NANOSECONDS)
    }

    /** Starts the span of [update] and makes it current while the bot dispatches the update. */
    override fun updateContext(update: Update): CoroutineContext {
        val parent = Context.current()
        val span = updateSpan(update).setParent(parent).startSpan()
        return parent.with(span).with(UPDATE, update).asContextElement()
    }

    override fun onUpdate(event: UpdateEvent) {
        val current = Context.current()
        if (current.get(UPDATE) === event.update) {
            val span = Span.fromContext(current)
            finish(span, event)
            span.end()
            return
        }
        // updateContext was not called for this update, e.g. a listener of the application passed only
        // the events on: the span is recorded afterwards, without children.
        val end = epochNanos()
        val span = updateSpan(event.update)
            .setStartTimestamp(end - event.duration.toNanos(), TimeUnit.NANOSECONDS)
            .startSpan()
        finish(span, event)
        span.end(end, TimeUnit.NANOSECONDS)
    }

    override fun onWebhookResubscribe(event: WebhookResubscribeEvent) {
        val span = tracer.spanBuilder(RESUBSCRIBE_SPAN)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(URL_FULL, event.url)
            .startSpan()
        event.error?.let { fail(span, it) }
        span.end()
    }

    private fun updateSpan(update: Update) = tracer.spanBuilder("$PROCESS ${update.updateType}")
        .setSpanKind(SpanKind.CONSUMER)
        .setAttribute(MESSAGING_SYSTEM, MESSAGING_SYSTEM_MAX)
        .setAttribute(MESSAGING_OPERATION_TYPE, PROCESS)
        .setAttribute(MESSAGING_OPERATION_NAME, PROCESS)
        .setAttribute(UPDATE_TYPE, update.updateType)
        .apply { update.chatId?.let { setAttribute(CHAT_ID, it) } }

    private fun finish(span: Span, event: UpdateEvent) {
        span.setAttribute(UPDATE_HANDLED, event.handled)
        event.error?.let { fail(span, it) }
    }

    private fun fail(span: Span, error: Throwable) {
        span.setAttribute(ERROR_TYPE, error.javaClass.name)
        span.recordException(error)
        span.setStatus(StatusCode.ERROR, error.message.orEmpty())
    }

    private fun epochNanos(): Long {
        val now = Instant.now()
        return TimeUnit.SECONDS.toNanos(now.epochSecond) + now.nano
    }

    public companion object {
        /** Name of the instrumentation scope (the tracer) of the spans. */
        public const val INSTRUMENTATION_SCOPE: String = "ru.securitytrip.maxapi"

        /** Name of the span of a webhook subscription renewed after Max dropped it. */
        public const val RESUBSCRIBE_SPAN: String = "max.webhook.resubscribe"

        /** `messaging.system` of update spans. */
        public const val MESSAGING_SYSTEM_MAX: String = "max"

        /** `update_type` of the update, like `message_created`. */
        @JvmField
        public val UPDATE_TYPE: AttributeKey<String> = AttributeKey.stringKey("max.update.type")

        /** Chat of the update, when it has one. */
        @JvmField
        public val CHAT_ID: AttributeKey<Long> = AttributeKey.longKey("max.chat.id")

        /** Whether a handler matched the update; `false` also for a dropped duplicate. */
        @JvmField
        public val UPDATE_HANDLED: AttributeKey<Boolean> = AttributeKey.booleanKey("max.update.handled")

        private const val PROCESS = "process"
        private val UPDATE = ContextKey.named<Update>("maxapi-update")

        private val HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method")
        private val HTTP_RESPONSE_STATUS_CODE = AttributeKey.longKey("http.response.status_code")
        private val HTTP_REQUEST_RESEND_COUNT = AttributeKey.longKey("http.request.resend_count")
        private val URL_TEMPLATE = AttributeKey.stringKey("url.template")
        private val URL_FULL = AttributeKey.stringKey("url.full")
        private val SERVER_ADDRESS = AttributeKey.stringKey("server.address")
        private val SERVER_PORT = AttributeKey.longKey("server.port")
        private val ERROR_TYPE = AttributeKey.stringKey("error.type")
        private val MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system")
        private val MESSAGING_OPERATION_TYPE = AttributeKey.stringKey("messaging.operation.type")
        private val MESSAGING_OPERATION_NAME = AttributeKey.stringKey("messaging.operation.name")
    }
}
