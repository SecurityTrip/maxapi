package ru.securitytrip.maxapi.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.client.MaxApiException
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.client.RetryPolicy
import ru.securitytrip.maxapi.events.RequestEvent
import ru.securitytrip.maxapi.events.UpdateEvent
import ru.securitytrip.maxapi.events.WebhookResubscribeEvent
import ru.securitytrip.maxapi.model.update.Update
import java.io.IOException
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxTracingTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    private val openTelemetry = object : OpenTelemetry {
        override fun getTracerProvider(): TracerProvider = this@MaxTracingTest.tracerProvider
        override fun getPropagators(): ContextPropagators = ContextPropagators.noop()
    }
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        tracerProvider.close()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun client(tracing: MaxTracing) = MaxClient(
        MaxClientConfig.builder("token")
            .baseUrl(server.url("/").toString())
            .retryPolicy(RetryPolicy(maxRetries = 1, initialDelay = Duration.ofMillis(1)))
            .eventListener(tracing)
            .build(),
    )

    private fun message(text: String, chatId: Long = 100): Update = MaxJson.parseUpdate(
        """{"update_type":"message_created","timestamp":1,"message":{"sender":{"user_id":1,"first_name":"Ann"},
            "recipient":{"chat_id":$chatId,"chat_type":"dialog"},"timestamp":1,"body":{"mid":"mid.$text","seq":1,"text":"$text"}}}""",
    )

    private fun spans(): List<SpanData> = exporter.finishedSpanItems

    private fun SpanData.attribute(name: String): Any? = attributes.asMap().entries.firstOrNull { it.key.key == name }?.value

    @Test
    fun `requests of a handler are children of the update span`(): Unit = runBlocking {
        val tracing = MaxTracing(openTelemetry, server.url("/").toString())
        val client = client(tracing)
        val bot = MaxBot(client, BotConfig(eventListener = tracing))
        server.enqueue(json("""{"success":true}"""))
        bot.onMessage { client.deleteMessage("mid.1") }

        bot.processUpdate(message("hi"))

        val (request, update) = spans()
        assertEquals("process message_created", update.name)
        assertEquals(SpanKind.CONSUMER, update.kind)
        assertEquals("max", update.attribute("messaging.system"))
        assertEquals("process", update.attribute("messaging.operation.type"))
        assertEquals("process", update.attribute("messaging.operation.name"))
        assertEquals("message_created", update.attributes.get(MaxTracing.UPDATE_TYPE))
        assertEquals(100L, update.attributes.get(MaxTracing.CHAT_ID))
        assertEquals(true, update.attributes.get(MaxTracing.UPDATE_HANDLED))
        assertEquals(StatusCode.UNSET, update.status.statusCode)
        assertFalse(update.parentSpanContext.isValid)

        assertEquals("DELETE /messages", request.name)
        assertEquals(SpanKind.CLIENT, request.kind)
        assertEquals("DELETE", request.attribute("http.request.method"))
        assertEquals("/messages", request.attribute("url.template"))
        assertEquals(200L, request.attribute("http.response.status_code"))
        assertEquals(server.hostName, request.attribute("server.address"))
        assertEquals(server.port.toLong(), request.attribute("server.port"))
        assertNull(request.attribute("http.request.resend_count"))
        assertEquals(update.traceId, request.traceId)
        assertEquals(update.spanId, request.parentSpanId)
        assertTrue(request.endEpochNanos >= request.startEpochNanos)
        bot.close()
        client.close()
    }

    @Test
    fun `the update span continues the current trace`(): Unit = runBlocking {
        val tracing = MaxTracing(openTelemetry)
        val bot = MaxBot("token", BotConfig(eventListener = tracing))
        bot.onMessage { }
        val parent = tracerProvider.get("test").spanBuilder("POST /max/webhook").setSpanKind(SpanKind.SERVER).startSpan()
        Context.current().with(parent).makeCurrent().use {
            bot.processUpdate(message("hi"))
        }
        parent.end()

        val (update, server) = spans()
        assertEquals(server.spanId, update.parentSpanId)
        assertEquals(server.traceId, update.traceId)
        bot.close()
    }

    @Test
    fun `failed and unhandled updates`(): Unit = runBlocking {
        val tracing = MaxTracing(openTelemetry)
        val bot = MaxBot("token", BotConfig(eventListener = tracing))
        bot.onError { _, _ -> }
        bot.onCommand("fail") { throw IllegalStateException("boom") }
        bot.processUpdate(message("/fail"))
        bot.processUpdate(MaxJson.parseUpdate("""{"update_type":"brand_new","timestamp":1}"""))

        val (failed, unhandled) = spans()
        assertEquals(StatusCode.ERROR, failed.status.statusCode)
        assertEquals("boom", failed.status.description)
        assertEquals("java.lang.IllegalStateException", failed.attribute("error.type"))
        assertEquals("exception", failed.events.single().name)
        assertEquals("process brand_new", unhandled.name)
        assertEquals(false, unhandled.attributes.get(MaxTracing.UPDATE_HANDLED))
        assertNull(unhandled.attributes.get(MaxTracing.CHAT_ID))
        bot.close()
    }

    @Test
    fun `error statuses, network errors and retries`(): Unit = runBlocking {
        val tracing = MaxTracing(openTelemetry, server.url("/").toString())
        val client = client(tracing)
        server.enqueue(json("""{"code":"internal","message":"busy"}""", code = 503))
        server.enqueue(json("""{"code":"not.found","message":"no chat"}""", code = 404))
        assertFailsWith<MaxApiException> { client.getChat(42) }

        val (first, retry) = spans()
        assertEquals("GET /chats/{id}", first.name)
        assertEquals("503", first.attribute("error.type"))
        assertEquals(StatusCode.ERROR, first.status.statusCode)
        assertEquals(1L, retry.attribute("http.request.resend_count"))
        assertEquals("404", retry.attribute("error.type"))

        exporter.reset()
        tracing.onRequest(RequestEvent("GET", "/me", null, Duration.ofMillis(5), 0, IOException()))
        val network = spans().single()
        assertEquals("java.io.IOException", network.attribute("error.type"))
        assertNull(network.attribute("http.response.status_code"))
        assertEquals("", network.status.description)
        assertEquals(5_000_000, network.endEpochNanos - network.startEpochNanos)
        client.close()
    }

    @Test
    fun `server address and port come from the base URL`() {
        fun serverOf(baseUrl: String): Pair<Any?, Any?> {
            exporter.reset()
            MaxTracing(openTelemetry, baseUrl).onRequest(RequestEvent("GET", "/me", 200, Duration.ZERO, 0, null))
            val span = spans().single()
            return span.attribute("server.address") to span.attribute("server.port")
        }
        assertEquals("platform-api2.max.ru" to 443L, serverOf(MaxClientConfig.DEFAULT_BASE_URL))
        assertEquals("localhost" to 80L, serverOf("http://localhost/"))
        assertEquals("proxy" to 8080L, serverOf("http://proxy:8080/max"))
        assertEquals(null to null, serverOf("file:/api"))
    }

    @Test
    fun `update events without the update context are recorded afterwards`() {
        val tracing = MaxTracing(openTelemetry)
        tracing.onUpdate(UpdateEvent(message("hi"), true, Duration.ofMillis(3), null))
        tracing.onUpdate(UpdateEvent(message("hi"), true, Duration.ZERO, RuntimeException("boom")))

        val (ok, failed) = spans()
        assertEquals("process message_created", ok.name)
        assertEquals(3_000_000, ok.endEpochNanos - ok.startEpochNanos)
        assertEquals("java.lang.RuntimeException", failed.attribute("error.type"))
    }

    @Test
    fun `renewed webhook subscriptions`() {
        val tracing = MaxTracing(openTelemetry)
        tracing.onWebhookResubscribe(WebhookResubscribeEvent("https://bot.example/hook", null))
        tracing.onWebhookResubscribe(WebhookResubscribeEvent("https://bot.example/hook", IOException("down")))

        val (renewed, failed) = spans()
        assertEquals(MaxTracing.RESUBSCRIBE_SPAN, renewed.name)
        assertEquals(SpanKind.INTERNAL, renewed.kind)
        assertEquals("https://bot.example/hook", renewed.attributes.get(AttributeKey.stringKey("url.full")))
        assertEquals(StatusCode.UNSET, renewed.status.statusCode)
        assertEquals(StatusCode.ERROR, failed.status.statusCode)
        assertEquals("down", failed.status.description)
    }
}
