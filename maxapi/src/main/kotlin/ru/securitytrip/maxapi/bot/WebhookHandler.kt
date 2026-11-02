package ru.securitytrip.maxapi.bot

import kotlinx.coroutines.runBlocking
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.webapp.Hmac
import java.security.MessageDigest

/**
 * Connects a [MaxBot] to webhook requests received by your own HTTP server
 * (Spring, Ktor, Javalin, a servlet, and so on). Get one with [MaxBot.webhookHandler].
 *
 * Max retries a delivery until it gets `200 OK`, for up to 8 hours. So respond with 200 to every
 * request you accepted, even if handling it failed (the failure goes to `MaxBot.onError`), and with
 * 401 only when the secret check fails.
 *
 * Spring:
 * ```java
 * @PostMapping("/max/webhook")
 * ResponseEntity<Void> webhook(@RequestBody String body,
 *                              @RequestHeader(value = WebhookHandler.SECRET_HEADER, required = false) String secret) {
 *     return handler.handleAsync(body, secret) ? ResponseEntity.ok().build() : ResponseEntity.status(401).build();
 * }
 * ```
 *
 * Ktor:
 * ```kotlin
 * post("/max/webhook") {
 *     val ok = handler.handle(call.receiveText(), call.request.headers[WebhookHandler.SECRET_HEADER])
 *     call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.Unauthorized)
 * }
 * ```
 *
 * Choose the method by how long your handlers take:
 *
 * - [handle] / [handleBlocking] run the handler before returning: the response waits for it;
 * - [handleAsync] only parses the update and queues it, so the response is immediate. Queued updates
 *   are handled as in long polling: in order within a chat, up to [BotConfig.parallelism] handlers at once.
 */
public class WebhookHandler internal constructor(
    private val bot: MaxBot,
    secret: String?,
) {
    private val secretBytes = secret?.toByteArray(Charsets.UTF_8)

    init {
        if (secret == null) {
            log.log(
                System.Logger.Level.WARNING,
                "The webhook handler has no secret and accepts updates from anyone who knows its URL; " +
                    "subscribe with a secret, e.g. WebhookHandler.secretFor(token)",
            )
        }
    }

    /**
     * Checks the value of the [SECRET_HEADER] header in constant time.
     * Always `true` if the handler was created without a secret.
     */
    public fun verifySecret(secretHeader: String?): Boolean {
        val expected = secretBytes ?: return true
        if (secretHeader == null) return false
        return MessageDigest.isEqual(expected, secretHeader.toByteArray(Charsets.UTF_8))
    }

    /**
     * Verifies the secret, parses the update and runs the matching handler before returning.
     *
     * @param body request body.
     * @param secretHeader value of the [SECRET_HEADER] header, or `null` if it is absent.
     * @return `false` if the secret does not match (respond with 401); `true` otherwise (respond with 200).
     * @throws ru.securitytrip.maxapi.client.MaxException if the body is not a valid update.
     */
    public suspend fun handle(body: String, secretHeader: String?): Boolean {
        if (!verifySecret(secretHeader)) return false
        bot.processUpdate(MaxJson.parseUpdate(body))
        return true
    }

    /** Same as [handle] for a body read as bytes. */
    public suspend fun handle(body: ByteArray, secretHeader: String?): Boolean {
        if (!verifySecret(secretHeader)) return false
        bot.processUpdate(MaxJson.parseUpdate(body))
        return true
    }

    /** Blocking version of [handle] for Java and servlet containers. */
    public fun handleBlocking(body: String, secretHeader: String?): Boolean =
        runBlocking { handle(body, secretHeader) }

    /** Blocking version of [handle] for a body read as bytes. */
    public fun handleBlocking(body: ByteArray, secretHeader: String?): Boolean =
        runBlocking { handle(body, secretHeader) }

    /**
     * Verifies the secret and parses the update immediately, then queues it and returns without waiting
     * for the handler. Updates of a chat are handled one at a time and in order, and at most
     * [BotConfig.parallelism] handlers run at once.
     *
     * @return `false` if the secret does not match (respond with 401); `true` otherwise (respond with 200).
     * @throws ru.securitytrip.maxapi.client.MaxException if the body is not a valid update.
     * @throws IllegalStateException if the bot is closed, or if the chat of the update already has
     *   [BotConfig.queueCapacity] updates waiting; respond with an error such as 503 so that Max redelivers.
     */
    public fun handleAsync(body: String, secretHeader: String?): Boolean {
        if (!verifySecret(secretHeader)) return false
        bot.launchProcessing(MaxJson.parseUpdate(body))
        return true
    }

    /** Same as [handleAsync] for a body read as bytes. */
    public fun handleAsync(body: ByteArray, secretHeader: String?): Boolean {
        if (!verifySecret(secretHeader)) return false
        bot.launchProcessing(MaxJson.parseUpdate(body))
        return true
    }

    public companion object {
        /** Header in which Max sends the secret of the webhook subscription. */
        public const val SECRET_HEADER: String = "X-Max-Bot-Api-Secret"

        private val log: System.Logger = System.getLogger("ru.securitytrip.maxapi.bot")

        /**
         * A webhook secret derived from [botToken]: `hex(HMAC_SHA256(botToken, "max-webhook-secret"))`, 64
         * characters of `[0-9a-f]`. It is the same on every instance of the bot, needs no configuration, and
         * cannot be guessed without the token. The Spring Boot starter uses it when `max.bot.webhook.secret`
         * is not set.
         *
         * @throws IllegalArgumentException if [botToken] is blank.
         */
        @JvmStatic
        public fun secretFor(botToken: String): String {
            Hmac.requireToken(botToken)
            return Hmac.hex(botToken.toByteArray(Charsets.UTF_8), "max-webhook-secret")
        }
    }
}
