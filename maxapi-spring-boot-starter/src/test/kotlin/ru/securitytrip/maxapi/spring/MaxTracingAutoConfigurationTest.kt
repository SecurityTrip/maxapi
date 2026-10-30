package ru.securitytrip.maxapi.spring

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.securitytrip.maxapi.bot.MaxBot
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.opentelemetry.MaxTracing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaxTracingAutoConfigurationTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    private val openTelemetry = object : OpenTelemetry {
        override fun getTracerProvider(): TracerProvider = this@MaxTracingAutoConfigurationTest.tracerProvider
        override fun getPropagators(): ContextPropagators = ContextPropagators.noop()
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MaxBotAutoConfiguration::class.java, MaxTracingAutoConfiguration::class.java))
        .withPropertyValues("max.bot.token=t", "max.bot.mode=none")

    private val update = """{"update_type":"message_created","timestamp":1,"message":{"sender":{"user_id":1},
        "recipient":{"chat_id":5,"chat_type":"dialog"},"timestamp":1,"body":{"mid":"m","seq":1,"text":"hi"}}}"""

    @Test
    fun `updates are traced with an OpenTelemetry bean`() {
        runner.withBean(OpenTelemetry::class.java, { openTelemetry })
            .withBean(MaxBotConfigurer::class.java, { MaxBotConfigurer { it.onMessage { } } })
            .run { context ->
                assertTrue(context.getBeansOfType(MaxTracing::class.java).isNotEmpty())
                context.getBean(MaxBot::class.java).processUpdateBlocking(MaxJson.parseUpdate(update))
                assertEquals("process message_created", exporter.finishedSpanItems.single().name)
            }
    }

    @Test
    fun `no tracing without OpenTelemetry or the tracing module`() {
        runner.run { context -> assertFalse(context.containsBean("maxTracing")) }
        runner.withBean(OpenTelemetry::class.java, { openTelemetry })
            .withClassLoader(FilteredClassLoader(MaxTracing::class.java))
            .run { context -> assertFalse(context.containsBean("maxTracing")) }
    }
}
