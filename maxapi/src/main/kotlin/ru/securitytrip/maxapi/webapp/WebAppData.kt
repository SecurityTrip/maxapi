package ru.securitytrip.maxapi.webapp

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import ru.securitytrip.maxapi.client.MaxException
import ru.securitytrip.maxapi.client.MaxJson
import ru.securitytrip.maxapi.model.chat.ChatType
import java.time.Duration
import java.time.Instant

/**
 * Launch data of a mini app, checked by [validate]: `window.WebApp.initData` that the mini app sends
 * to its backend. Only data that passed [validate] can be trusted; `initDataUnsafe` of the frontend
 * cannot.
 *
 * ```kotlin
 * val data = WebAppData.validate(initData, botToken)          // throws WebAppDataException
 * println("Hello, ${data.user?.firstName} from chat ${data.chat?.id}")
 * ```
 *
 * ```java
 * WebAppData data = WebAppData.validate(initData, botToken, Duration.ofMinutes(30));
 * ```
 *
 * The check follows [the Max documentation](https://dev.max.ru/docs/webapps/validation): the
 * fields are URL-decoded once, `hash` is taken out, the rest are sorted by key and joined as
 * `key=value` lines, and `hash` must equal `hex(HMAC_SHA256(HMAC_SHA256("WebAppData", botToken), lines))`.
 *
 * @property user the user who opened the mini app.
 * @property chat the chat the mini app was opened from.
 * @property queryId unique identifier of this launch of the mini app.
 * @property authDate when Max issued the data.
 * @property startParam the `startapp` parameter of the link that opened the mini app (see [MaxLinks.miniApp]).
 * @property ip IP address of the user, as Max saw it.
 * @property hash the signature that was checked.
 * @property fields all decoded fields except `hash`, including those this class has no property for.
 */
public data class WebAppData(
    val user: WebAppUser?,
    val chat: WebAppChat?,
    val queryId: String?,
    val authDate: Instant,
    val startParam: String?,
    val ip: String?,
    val hash: String,
    val fields: Map<String, String>,
) {
    public companion object {
        /** How old data [validate] accepts by default: one hour, as the Max documentation recommends. */
        @JvmField
        public val DEFAULT_MAX_AGE: Duration = Duration.ofHours(1)

        /**
         * Checks the signature and the age of [initData] and returns its fields.
         *
         * @param initData `window.WebApp.initData` exactly as the mini app sent it.
         * @param botToken token of the bot the mini app belongs to.
         * @param maxAge how old `auth_date` may be.
         * @param now the current time; tests pass a fixed one.
         * @throws WebAppDataException with a [WebAppDataException.Reason] if the data is malformed,
         *   has no or several `hash` fields, is signed with another token or was changed, or is older than [maxAge].
         * @throws IllegalArgumentException if [botToken] is blank or [maxAge] is negative.
         */
        @JvmStatic
        @JvmOverloads
        public fun validate(
            initData: String,
            botToken: String,
            maxAge: Duration = DEFAULT_MAX_AGE,
            now: Instant = Instant.now(),
        ): WebAppData {
            require(!maxAge.isNegative) { "maxAge must not be negative: $maxAge" }
            val fields = InitDataSignature.check(initData, botToken)
            val authDate = fields["auth_date"]?.toLongOrNull()?.let(Instant::ofEpochSecond)
                ?: throw WebAppDataException(WebAppDataException.Reason.MALFORMED, "initData has no valid auth_date")
            if (Duration.between(authDate, now) > maxAge) {
                throw WebAppDataException(
                    WebAppDataException.Reason.EXPIRED,
                    "initData was issued at $authDate, more than $maxAge before $now",
                )
            }
            return WebAppData(
                user = fields["user"]?.let { json(it, "user") }?.let(::user),
                chat = fields["chat"]?.let { json(it, "chat") }?.let(::chat),
                queryId = fields["query_id"],
                authDate = authDate,
                startParam = fields["start_param"],
                ip = fields["ip"],
                hash = fields.getValue(InitDataSignature.HASH),
                fields = fields - InitDataSignature.HASH,
            )
        }

        /**
         * Only checks the signature of [initData]: neither the age of `auth_date` nor the format of
         * the fields. Returns `false` instead of throwing, also for malformed data. Prefer [validate],
         * which rejects old data that could have been intercepted.
         */
        @JvmStatic
        public fun verifySignature(initData: String, botToken: String): Boolean = try {
            InitDataSignature.check(initData, botToken)
            true
        } catch (_: WebAppDataException) {
            false
        }

        private fun json(value: String, field: String): JsonNode = try {
            MaxJson.mapper.readTree(value)
        } catch (e: JacksonException) {
            throw WebAppDataException(WebAppDataException.Reason.MALFORMED, "Field $field of initData is not JSON", e)
        }

        private fun user(node: JsonNode): WebAppUser = WebAppUser(
            userId = node.id("user"),
            firstName = node.text("first_name").orEmpty(),
            lastName = node.text("last_name"),
            username = node.text("username"),
            languageCode = node.text("language_code"),
            photoUrl = node.text("photo_url"),
        )

        private fun chat(node: JsonNode): WebAppChat {
            val type = node.text("type")
            return WebAppChat(
                id = node.id("chat"),
                type = ChatType.entries.firstOrNull { it.value.equals(type, ignoreCase = true) } ?: ChatType.UNKNOWN,
            )
        }

        private fun JsonNode.id(field: String): Long = get("id")?.takeIf { it.canConvertToExactIntegral() }?.asLong()
            ?: throw WebAppDataException(WebAppDataException.Reason.MALFORMED, "Field $field of initData has no numeric id")

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()
    }
}

/**
 * The user who opened a mini app, from [WebAppData.user].
 *
 * @property languageCode language of the user's Max client, e.g. `ru`.
 * @property photoUrl URL of the user's avatar, if they have one.
 */
public data class WebAppUser(
    val userId: Long,
    val firstName: String,
    val lastName: String?,
    val username: String?,
    val languageCode: String?,
    val photoUrl: String?,
)

/**
 * The chat a mini app was opened from, from [WebAppData.chat].
 *
 * @property type the kind of chat; Max sends it in upper case (`DIALOG`), which is matched to [ChatType] ignoring case.
 */
public data class WebAppChat(
    val id: Long,
    val type: ChatType,
)

/**
 * `initData` of a mini app failed [WebAppData.validate]; [reason] tells why.
 * The message never contains the bot token.
 */
public class WebAppDataException(
    public val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : MaxException(message, cause) {
    /** Why `initData` was rejected. */
    public enum class Reason {
        /** Not a query string, a field that is not JSON or not a number, or a field repeated. */
        MALFORMED,

        /** There is no `hash` field. */
        MISSING_HASH,

        /** The `hash` field appears more than once. */
        DUPLICATE_HASH,

        /** `hash` does not match: the data was changed or signed with the token of another bot. */
        INVALID_SIGNATURE,

        /** `auth_date` is older than the allowed age. */
        EXPIRED,
    }
}
