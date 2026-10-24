package ru.securitytrip.maxapi.client

import okhttp3.OkHttpClient
import ru.securitytrip.maxapi.events.MaxEventListener
import java.time.Duration
import kotlin.math.min
import kotlin.math.pow

/**
 * Settings of a [MaxClient].
 *
 * Kotlin code usually passes named arguments; Java code uses [builder]:
 *
 * ```kotlin
 * val config = MaxClientConfig(token, retryPolicy = RetryPolicy(maxRetries = 5))
 * ```
 *
 * ```java
 * MaxClientConfig config = MaxClientConfig.builder(token)
 *         .retryPolicy(new RetryPolicy(5))
 *         .build();
 * ```
 */
public class MaxClientConfig(
    /** Bot access token from [business.max.ru](https://business.max.ru/). Must not be blank. */
    public val token: String,
    /** Base URL of the API. Override it only for tests or proxies. */
    public val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * Your own `OkHttpClient`, e.g. with a proxy, logging interceptor or custom timeouts.
     *
     * If it is not set, the client creates one (15 s connect, 60 s read/write timeouts) and shuts it
     * down in [MaxClient.close]. A client you pass in is never closed by the library. In both cases,
     * `getUpdates` automatically uses a longer read timeout so that long polling works.
     */
    public val httpClient: OkHttpClient? = null,
    /**
     * Retries on HTTP 429, 502, 503, 504 for every request, and on network errors for
     * idempotent requests (GET, PUT, DELETE). POST and PATCH are never retried after a network
     * error, because the server may have already processed them.
     */
    public val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    /**
     * How many times `sendMessage`, `editMessage` and `answerOnCallback` are retried while the server
     * answers `attachment.not.ready` (an uploaded file is still being processed).
     */
    public val attachmentReadyRetries: Int = 5,
    /** Pause between `attachment.not.ready` retries. */
    public val attachmentReadyDelay: Duration = Duration.ofSeconds(1),
    /** Value of the `User-Agent` header. */
    public val userAgent: String = DEFAULT_USER_AGENT,
    /** Client-side request rate limit; `null` (default) sends requests as fast as they come. */
    public val rateLimit: RateLimit? = null,
    /** Receives an event for every HTTP attempt, e.g. for metrics. */
    public val eventListener: MaxEventListener = MaxEventListener.NONE,
) {
    init {
        require(token.isNotBlank()) { "token must not be blank" }
        require(attachmentReadyRetries >= 0) { "attachmentReadyRetries must be >= 0" }
    }

    /** Never includes the token, so the config is safe to log. */
    override fun toString(): String = "MaxClientConfig(baseUrl=$baseUrl, retryPolicy=$retryPolicy, rateLimit=$rateLimit)"

    /** Builder for Java. Every setter has the same meaning as the matching constructor parameter. */
    public class Builder(private val token: String) {
        private var baseUrl = DEFAULT_BASE_URL
        private var httpClient: OkHttpClient? = null
        private var retryPolicy = RetryPolicy.DEFAULT
        private var attachmentReadyRetries = 5
        private var attachmentReadyDelay = Duration.ofSeconds(1)
        private var userAgent = DEFAULT_USER_AGENT
        private var rateLimit: RateLimit? = null
        private var eventListener = MaxEventListener.NONE

        public fun baseUrl(value: String): Builder = apply { baseUrl = value }
        public fun httpClient(value: OkHttpClient): Builder = apply { httpClient = value }
        public fun retryPolicy(value: RetryPolicy): Builder = apply { retryPolicy = value }
        public fun attachmentReadyRetries(value: Int): Builder = apply { attachmentReadyRetries = value }
        public fun attachmentReadyDelay(value: Duration): Builder = apply { attachmentReadyDelay = value }
        public fun userAgent(value: String): Builder = apply { userAgent = value }
        public fun rateLimit(value: RateLimit?): Builder = apply { rateLimit = value }
        public fun eventListener(value: MaxEventListener): Builder = apply { eventListener = value }

        public fun build(): MaxClientConfig = MaxClientConfig(
            token = token,
            baseUrl = baseUrl,
            httpClient = httpClient,
            retryPolicy = retryPolicy,
            attachmentReadyRetries = attachmentReadyRetries,
            attachmentReadyDelay = attachmentReadyDelay,
            userAgent = userAgent,
            rateLimit = rateLimit,
            eventListener = eventListener,
        )
    }

    public companion object {
        /** Production API endpoint. */
        public const val DEFAULT_BASE_URL: String = "https://platform-api2.max.ru"

        public const val DEFAULT_USER_AGENT: String = "maxapi-jvm"

        /** Starts building a config for Java code. */
        @JvmStatic
        public fun builder(token: String): Builder = Builder(token)
    }
}

/**
 * Exponential backoff for retried requests.
 *
 * The delay before retry number `n` (zero-based) is `initialDelay * multiplier^n`, capped at `maxDelay`.
 * If the server sends `Retry-After`, that value is used instead. When it is longer than `maxDelay`, the
 * request is not retried at all: retrying earlier than the server allows would only fail again.
 * With the defaults, the delays are 0.5 s, 1 s and 2 s.
 *
 * @property maxRetries how many times to retry after the first attempt; `0` disables retries.
 */
public class RetryPolicy @JvmOverloads constructor(
    public val maxRetries: Int = 3,
    public val initialDelay: Duration = Duration.ofMillis(500),
    public val maxDelay: Duration = Duration.ofSeconds(30),
    public val multiplier: Double = 2.0,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
    }

    /**
     * Delay before retry number [attempt] (zero-based). The server's `Retry-After` takes precedence;
     * `null` means do not retry, because `Retry-After` is longer than [maxDelay].
     */
    internal fun delayMillis(attempt: Int, retryAfter: Duration?): Long? {
        if (retryAfter != null) return retryAfter.toMillis().takeIf { it <= maxDelay.toMillis() }
        return backoffMillis(attempt)
    }

    /** Exponential delay before retry number [attempt] (zero-based), capped at [maxDelay]. */
    internal fun backoffMillis(attempt: Int): Long {
        val delay = initialDelay.toMillis() * multiplier.pow(attempt)
        return min(delay.toLong(), maxDelay.toMillis())
    }

    override fun toString(): String =
        "RetryPolicy(maxRetries=$maxRetries, initialDelay=$initialDelay, maxDelay=$maxDelay, multiplier=$multiplier)"

    public companion object {
        /** 3 retries starting at 500 ms. */
        @JvmField
        public val DEFAULT: RetryPolicy = RetryPolicy()

        /** No retries. */
        @JvmField
        public val NONE: RetryPolicy = RetryPolicy(maxRetries = 0)
    }
}
