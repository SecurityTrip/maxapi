package ru.securitytrip.maxapi.client

import java.io.IOException
import java.time.Duration

/**
 * Base class of all exceptions thrown by the library.
 *
 * All library exceptions are unchecked, so they can be caught from Java without `throws` clauses:
 *
 * - [MaxApiException] and its subclasses: the server answered with an error;
 * - [MaxNetworkException]: the server could not be reached;
 * - plain [MaxException]: the response could not be parsed, or another library-level failure.
 */
public open class MaxException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The request did not reach the server or the response was not received: connection refused,
 * DNS failure, timeout, broken connection. The original [IOException] is the `cause`.
 *
 * Idempotent requests (GET, PUT, DELETE) are retried according to [RetryPolicy] before this is thrown.
 */
public class MaxNetworkException(message: String, cause: IOException) : MaxException(message, cause)

/**
 * The server answered with an HTTP error.
 *
 * Common statuses have their own subclasses: [UnauthorizedException] (401), [ForbiddenException] (403),
 * [NotFoundException] (404) and [TooManyRequestsException] (429).
 *
 * ```kotlin
 * try {
 *     client.sendMessageToUser(userId, "Hi")
 * } catch (e: ForbiddenException) {
 *     // the user stopped the bot
 * } catch (e: MaxApiException) {
 *     log.warn("Max API error ${e.httpStatus} ${e.errorCode}: ${e.errorMessage}")
 * }
 * ```
 *
 * @property httpStatus HTTP status code of the response.
 * @property errorCode error key from the `code` field, e.g. `chat.denied`.
 * @property errorMessage human-readable description from the `message` field.
 * @property responseBody raw response body, useful for logging.
 */
public open class MaxApiException(
    public val httpStatus: Int,
    public val errorCode: String?,
    public val errorMessage: String?,
    public val responseBody: String?,
) : MaxException(
    buildString {
        append("HTTP ").append(httpStatus)
        if (errorCode != null) append(" [").append(errorCode).append(']')
        if (errorMessage != null) append(": ").append(errorMessage)
    },
) {
    /**
     * `true` if the request referenced media that the server is still processing.
     * `MaxClient` already retries such requests (see [MaxClientConfig.attachmentReadyRetries]),
     * so you only see this after all retries failed.
     */
    public val isAttachmentNotReady: Boolean
        get() = errorCode == ATTACHMENT_NOT_READY || errorMessage?.contains(ATTACHMENT_NOT_READY) == true

    internal companion object {
        const val ATTACHMENT_NOT_READY = "attachment.not.ready"

        fun of(status: Int, code: String?, message: String?, body: String?, retryAfter: Duration?): MaxApiException =
            when (status) {
                401 -> UnauthorizedException(code, message, body)
                403 -> ForbiddenException(code, message, body)
                404 -> NotFoundException(code, message, body)
                429 -> TooManyRequestsException(code, message, body, retryAfter)
                else -> MaxApiException(status, code, message, body)
            }
    }
}

/** HTTP 401: the access token is missing or invalid. */
public class UnauthorizedException(errorCode: String?, errorMessage: String?, responseBody: String?) :
    MaxApiException(401, errorCode, errorMessage, responseBody)

/**
 * HTTP 403: the bot has no access to the resource. For example, the user stopped the bot,
 * the bot is not a member or not an administrator of the chat, or forwarding is prohibited.
 */
public class ForbiddenException(errorCode: String?, errorMessage: String?, responseBody: String?) :
    MaxApiException(403, errorCode, errorMessage, responseBody)

/** HTTP 404: the chat, message or other resource does not exist or is not accessible to the bot. */
public class NotFoundException(errorCode: String?, errorMessage: String?, responseBody: String?) :
    MaxApiException(404, errorCode, errorMessage, responseBody)

/**
 * HTTP 429: the request rate limit is exceeded. Thrown only after [RetryPolicy] retries are exhausted.
 *
 * @property retryAfter value of the `Retry-After` header, if the server sent one.
 */
public class TooManyRequestsException(
    errorCode: String?,
    errorMessage: String?,
    responseBody: String?,
    public val retryAfter: Duration?,
) : MaxApiException(429, errorCode, errorMessage, responseBody)
