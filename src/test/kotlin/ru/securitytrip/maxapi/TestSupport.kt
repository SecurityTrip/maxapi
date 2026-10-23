package ru.securitytrip.maxapi

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import ru.securitytrip.maxapi.client.MaxClient
import ru.securitytrip.maxapi.client.MaxClientConfig
import ru.securitytrip.maxapi.client.RetryPolicy
import java.time.Duration
import java.util.concurrent.TimeUnit

const val TOKEN = "test-token"

fun json(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

fun MockWebServer.client(
    retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 2, initialDelay = Duration.ofMillis(1)),
): MaxClient = MaxClient(
    MaxClientConfig(
        token = TOKEN,
        baseUrl = url("/").toString(),
        retryPolicy = retryPolicy,
        attachmentReadyRetries = 2,
        attachmentReadyDelay = Duration.ofMillis(1),
    ),
)

fun MockWebServer.take(): RecordedRequest = requireNotNull(takeRequest(5, TimeUnit.SECONDS)) { "No request received" }

val RecordedRequest.bodyText: String get() = body?.utf8().orEmpty()

fun messageJson(chatId: Long = 100, text: String = "hi", mid: String = "mid.1"): String =
    """{"sender":{"user_id":1,"first_name":"Ann"},"recipient":{"chat_id":$chatId,"chat_type":"dialog"},
       "timestamp":1,"body":{"mid":"$mid","seq":1,"text":"$text"}}"""

const val SUCCESS = """{"success":true}"""
