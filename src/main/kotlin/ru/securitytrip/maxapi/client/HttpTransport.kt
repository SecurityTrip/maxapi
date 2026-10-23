package ru.securitytrip.maxapi.client

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.securitytrip.maxapi.model.ApiEnum
import ru.securitytrip.maxapi.model.ErrorResponse
import java.io.Closeable
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Query string parameters: `null` values are skipped, collections are joined with commas. */
internal class Query {
    private val params = mutableListOf<Pair<String, String>>()

    fun add(name: String, value: Any?): Query = apply {
        when (value) {
            null -> Unit
            is Collection<*> -> if (value.isNotEmpty()) params += name to value.joinToString(",") { format(it) }
            else -> params += name to format(value)
        }
    }

    fun applyTo(builder: HttpUrl.Builder) {
        for ((name, value) in params) builder.addQueryParameter(name, value)
    }

    private fun format(value: Any?): String = if (value is ApiEnum) value.value else value.toString()
}

internal class RawResponse(val code: Int, val body: String, val retryAfter: Duration?) {
    val isSuccessful: Boolean get() = code in 200..299
}

internal class HttpTransport(
    private val config: MaxClientConfig,
    private val mapper: ObjectMapper,
) : Closeable {
    private val ownsClient = config.httpClient == null
    private val baseUrl = config.baseUrl.toHttpUrl()

    private val http: OkHttpClient = config.httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Client with a read timeout long enough for long polling (up to 90 seconds). */
    private val pollingHttp: OkHttpClient by lazy {
        if (http.readTimeoutMillis == 0 || http.readTimeoutMillis >= POLLING_READ_TIMEOUT_MS) http
        else http.newBuilder().readTimeout(POLLING_READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS).build()
    }

    suspend fun <T> execute(
        method: String,
        path: List<String>,
        responseType: Class<T>,
        query: Query? = null,
        body: Any? = null,
        longPolling: Boolean = false,
    ): T {
        val url = baseUrl.newBuilder().apply {
            for (segment in path) addPathSegment(segment)
            query?.applyTo(this)
        }.build()
        val requestBody = when {
            body != null -> mapper.writeValueAsBytes(body).toRequestBody(JSON)
            method in METHODS_WITH_BODY -> ByteArray(0).toRequestBody(JSON)
            else -> null
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", config.token)
            .header("User-Agent", config.userAgent)
            .header("Accept", "application/json")
            .method(method, requestBody)
            .build()
        val client = if (longPolling) pollingHttp else http
        val policy = config.retryPolicy
        val operation = "$method /${path.joinToString("/")}"

        var attempt = 0
        while (true) {
            val raw = try {
                send(client, request)
            } catch (e: IOException) {
                if (method in IDEMPOTENT_METHODS && attempt < policy.maxRetries) {
                    val wait = policy.backoffMillis(attempt++)
                    log.log(System.Logger.Level.DEBUG, "$operation failed: ${e.message}; retry in $wait ms")
                    delay(wait)
                    continue
                }
                throw MaxNetworkException("$operation failed: ${e.message}", e)
            }
            if (raw.isSuccessful) return parse(raw.body, responseType, operation)

            val error = toApiException(raw)
            // 429 and 503 mean the request was rejected. A gateway error (502, 504) may come after the
            // backend already processed the request, so only idempotent requests are retried then.
            val retryable = raw.code in REJECTED_STATUSES || (raw.code in GATEWAY_STATUSES && method in IDEMPOTENT_METHODS)
            val wait = if (retryable && attempt < policy.maxRetries) policy.delayMillis(attempt, raw.retryAfter) else null
            if (wait != null) {
                attempt++
                log.log(System.Logger.Level.DEBUG, "$operation returned ${raw.code}; retry in $wait ms")
                delay(wait)
                continue
            }
            throw error
        }
    }

    /**
     * Uploads a file to the URL returned by `POST /uploads`. That URL belongs to a different host,
     * so the bot token is deliberately not sent there.
     */
    suspend fun upload(uploadUrl: String, fileName: String, content: RequestBody): String {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("data", fileName, content)
            .build()
        val request = Request.Builder()
            .url(uploadUrl)
            .header("User-Agent", config.userAgent)
            .post(multipart)
            .build()
        val raw = try {
            send(http, request)
        } catch (e: IOException) {
            throw MaxNetworkException("Upload of $fileName failed: ${e.message}", e)
        }
        if (!raw.isSuccessful) throw toApiException(raw)
        return raw.body
    }

    private suspend fun send(client: OkHttpClient, request: Request): RawResponse =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { RawResponse(it.code, it.body.string(), parseRetryAfter(it.header("Retry-After"))) }
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                        return
                    }
                    cont.resume(result)
                }
            })
        }

    private fun <T> parse(body: String, type: Class<T>, operation: String): T = try {
        mapper.readValue(body, type)
    } catch (e: JacksonException) {
        throw MaxException("$operation: cannot parse response: ${e.originalMessage}", e)
    }

    private fun toApiException(raw: RawResponse): MaxApiException {
        val error = try {
            mapper.readValue(raw.body, ErrorResponse::class.java)
        } catch (_: JacksonException) {
            null
        }
        return MaxApiException.of(
            status = raw.code,
            code = error?.code ?: error?.error,
            message = error?.message ?: raw.body.takeIf { it.isNotBlank() }?.take(500),
            body = raw.body,
            retryAfter = raw.retryAfter,
        )
    }

    override fun close() {
        if (!ownsClient) return
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

}

private val JSON = "application/json; charset=utf-8".toMediaType()
private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH")
private val IDEMPOTENT_METHODS = setOf("GET", "PUT", "DELETE")
private val REJECTED_STATUSES = setOf(429, 503)
private val GATEWAY_STATUSES = setOf(502, 504)
private const val POLLING_READ_TIMEOUT_MS = 100_000
private val log: System.Logger = System.getLogger("ru.securitytrip.maxapi.client")

/** `Retry-After` as delay-seconds or as an HTTP-date; `null` if absent or invalid. */
internal fun parseRetryAfter(value: String?, now: Instant = Instant.now()): Duration? {
    val text = value?.trim() ?: return null
    text.toLongOrNull()?.let { seconds -> return if (seconds >= 0) Duration.ofSeconds(seconds) else null }
    val date = try {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (_: DateTimeParseException) {
        return null
    }
    return Duration.between(now, date).takeUnless { it.isNegative } ?: Duration.ZERO
}
