package ru.securitytrip.maxapi

import okhttp3.OkHttpClient
import ru.securitytrip.maxapi.bot.BotConfig
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.RetryPolicy
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfigTest {
    @Test
    fun `client config validates its values`() {
        assertFailsWith<IllegalArgumentException> { MaxClientConfig(" ") }
        assertFailsWith<IllegalArgumentException> { MaxClientConfig("t", attachmentReadyRetries = -1) }
        assertFailsWith<IllegalArgumentException> { MaxClient("") }
    }

    @Test
    fun `client config builder sets every field`() {
        val http = OkHttpClient()
        val policy = RetryPolicy(1)
        val config = MaxClientConfig.builder("token")
            .baseUrl("https://example.test/")
            .httpClient(http)
            .retryPolicy(policy)
            .attachmentReadyRetries(7)
            .attachmentReadyDelay(Duration.ofMillis(250))
            .userAgent("agent")
            .build()

        assertEquals("token", config.token)
        assertEquals("https://example.test/", config.baseUrl)
        assertSame(http, config.httpClient)
        assertSame(policy, config.retryPolicy)
        assertEquals(7, config.attachmentReadyRetries)
        assertEquals(Duration.ofMillis(250), config.attachmentReadyDelay)
        assertEquals("agent", config.userAgent)
    }

    @Test
    fun `client config defaults`() {
        val config = MaxClientConfig("token")
        assertEquals("https://platform-api2.max.ru", config.baseUrl)
        assertSame(RetryPolicy.DEFAULT, config.retryPolicy)
        assertEquals(5, config.attachmentReadyRetries)
        assertEquals(Duration.ofSeconds(1), config.attachmentReadyDelay)
        assertEquals("maxapi-jvm", config.userAgent)
        MaxClient.create("token").use { assertEquals("MaxClient(https://platform-api2.max.ru)", it.toString()) }
        MaxClient.create(config).use { assertSame(config, it.config) }
    }

    @Test
    fun `facades are cached`() {
        MaxClient("token").use {
            assertSame(it.blocking(), it.blocking())
            assertSame(it.async(), it.async())
            assertSame(it, it.blocking().client)
            assertSame(it, it.async().client)
        }
    }

    @Test
    fun `retry policy validates its values`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxRetries = -1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(multiplier = 0.5) }
        assertTrue(RetryPolicy(2).toString().contains("maxRetries=2"))
    }

    @Test
    fun `bot config validates its values`() {
        assertFailsWith<IllegalArgumentException> { BotConfig(parallelism = 0) }
        assertFailsWith<IllegalArgumentException> { BotConfig(queueCapacity = 0) }
        assertFailsWith<IllegalArgumentException> { BotConfig(pollTimeoutSeconds = 91) }
        assertFailsWith<IllegalArgumentException> { BotConfig(pollTimeoutSeconds = -1) }
        assertFailsWith<IllegalArgumentException> { BotConfig(pollLimit = 0) }
        assertFailsWith<IllegalArgumentException> { BotConfig(pollLimit = 1001) }
    }

    @Test
    fun `bot config builder sets every field`() {
        val config = BotConfig.builder()
            .parallelism(8)
            .queueCapacity(16)
            .pollTimeoutSeconds(60)
            .pollLimit(500)
            .minPollInterval(Duration.ofSeconds(1))
            .maxErrorBackoff(Duration.ofMinutes(1))
            .build()

        assertEquals(8, config.parallelism)
        assertEquals(16, config.queueCapacity)
        assertEquals(60, config.pollTimeoutSeconds)
        assertEquals(500, config.pollLimit)
        assertEquals(Duration.ofSeconds(1), config.minPollInterval)
        assertEquals(Duration.ofMinutes(1), config.maxErrorBackoff)
    }
}
