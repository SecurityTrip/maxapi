package ru.securitytrip.maxapi.webapp

import ru.securitytrip.maxapi.client.MaxLimits
import java.net.URLEncoder

/**
 * Builds [deep links](https://dev.max.ru/help/deeplinks) of Max:
 *
 * ```kotlin
 * MaxLinks.bot("shop_bot")                          // https://max.ru/shop_bot
 * MaxLinks.botStart("shop_bot", "ref-42")           // https://max.ru/shop_bot?start=ref-42
 * MaxLinks.miniApp("shop_bot", "promo_summer")      // https://max.ru/shop_bot?startapp=promo_summer
 * MaxLinks.share("Look: https://example.com")       // https://max.ru/:share?text=Look%3A%20https%3A%2F%2Fexample.com
 * ```
 *
 * Max silently drops a payload that breaks its limits, so these methods throw
 * [IllegalArgumentException] instead of building such a link. Links work in Max for iOS 2.7.0 and
 * Android 2.9.0 and later.
 */
public object MaxLinks {
    /** Prefix of all links. */
    public const val BASE_URL: String = "https://max.ru/"

    private val BOT_NAME = Regex("[A-Za-z0-9_.-]+")

    /** A link to the chat with the bot [botName], its public name without `@`. */
    @JvmStatic
    public fun bot(botName: String): String = BASE_URL + checkBotName(botName)

    /**
     * A link that opens the chat with the bot and, when the user starts it, passes [payload] to the bot
     * in `BotStartedUpdate.payload`. [payload] is 1 to [MaxLimits.BOT_START_PAYLOAD_LENGTH] characters;
     * characters other than letters, digits, `_`, `-`, `.` and `~` are percent-encoded in the link.
     */
    @JvmStatic
    public fun botStart(botName: String, payload: String): String {
        require(payload.length in 1..MaxLimits.BOT_START_PAYLOAD_LENGTH) {
            "Start payload is ${payload.length} characters long; Max allows 1 to ${MaxLimits.BOT_START_PAYLOAD_LENGTH}"
        }
        return bot(botName) + "?start=" + encode(payload)
    }

    /**
     * A link that opens the mini app of the bot [botName]; the mini app gets [payload] as
     * `start_param` of its `initData` ([WebAppData.startParam]). [payload] is up to
     * [MaxLimits.OPEN_APP_PAYLOAD_LENGTH] characters of `A-Z`, `a-z`, `0-9`, `_` and `-`;
     * `null` or empty builds a link without one.
     */
    @JvmStatic
    @JvmOverloads
    public fun miniApp(botName: String, payload: String? = null): String {
        val link = bot(botName) + "?startapp"
        if (payload.isNullOrEmpty()) return link
        MaxLimits.checkOpenAppPayload(payload)
        return "$link=$payload"
    }

    /** A link that lets the user send [text] to a chat or channel they pick. */
    @JvmStatic
    public fun share(text: String): String {
        require(text.isNotEmpty()) { "Text to share must not be empty" }
        return BASE_URL + ":share?text=" + encode(text)
    }

    private fun checkBotName(botName: String): String {
        val name = botName.removePrefix("@")
        require(BOT_NAME.matches(name)) { "\"$botName\" is not a bot name: use its public name of letters, digits, _, . and -" }
        return name
    }

    /** Percent-encoding of RFC 3986: spaces become `%20`, not `+`. */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
}
