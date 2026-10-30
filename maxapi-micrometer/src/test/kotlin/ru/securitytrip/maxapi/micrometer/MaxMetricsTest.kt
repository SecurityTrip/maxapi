package ru.securitytrip.maxapi.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.events.RequestEvent
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.events.WebhookResubscribeEvent
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class MaxMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = MaxMetrics(registry)

    private fun request(status: Int?, attempt: Int = 0) =
        RequestEvent("GET", "/chats/{id}", status, Duration.ofMillis(5), attempt, if (status == null) IOException("x") else null)

    @Test
    fun `requests are timed with outcome tags`() {
        metrics.onRequest(request(200))
        metrics.onRequest(request(200))
        metrics.onRequest(request(404))
        metrics.onRequest(request(503, attempt = 1))
        metrics.onRequest(request(null))

        fun count(vararg tags: String) = registry.get(MaxMetrics.REQUESTS).tags(*tags).timer().count()
        assertEquals(2, count("outcome", "SUCCESS", "status", "200", "retry", "false", "method", "GET", "path", "/chats/{id}"))
        assertEquals(1, count("outcome", "CLIENT_ERROR", "status", "404"))
        assertEquals(1, count("outcome", "SERVER_ERROR", "status", "503", "retry", "true"))
        assertEquals(1, count("outcome", "NETWORK_ERROR", "status", "NONE"))
        assertEquals(10.0, registry.get(MaxMetrics.REQUESTS).tags("status", "200").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `updates are timed with type, handled and exception tags`() {
        val update = MaxJson.parseUpdate("""{"update_type":"bot_started","timestamp":1,"chat_id":1,"user":{"user_id":1}}""")
        metrics.onUpdate(UpdateEvent(update, true, Duration.ofMillis(3), null))
        metrics.onUpdate(UpdateEvent(update, false, Duration.ofMillis(1), null))
        metrics.onUpdate(UpdateEvent(update, true, Duration.ofMillis(2), IllegalStateException("boom")))

        fun count(vararg tags: String) = registry.get(MaxMetrics.UPDATES).tags(*tags).timer().count()
        assertEquals(1, count("type", "bot_started", "handled", "true", "outcome", "SUCCESS", "exception", "none"))
        assertEquals(1, count("handled", "false"))
        assertEquals(1, count("outcome", "ERROR", "exception", "IllegalStateException"))
    }

    @Test
    fun `renewed webhook subscriptions are counted`() {
        metrics.onWebhookResubscribe(WebhookResubscribeEvent("https://bot/hook", null))
        metrics.onWebhookResubscribe(WebhookResubscribeEvent("https://bot/hook", null))
        metrics.onWebhookResubscribe(WebhookResubscribeEvent("https://bot/hook", IOException("down")))
        fun count(vararg tags: String) = registry.get(MaxMetrics.RESUBSCRIPTIONS).tags(*tags).counter().count()
        assertEquals(2.0, count("outcome", "SUCCESS", "exception", "none"))
        assertEquals(1.0, count("outcome", "ERROR", "exception", "IOException"))
    }
}
