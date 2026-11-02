package ru.securitytrip.maxapi.webapp

import java.time.Duration
import java.time.Instant

/**
 * The result of `window.WebApp.requestContact()` in a mini app: the phone number the user shared,
 * signed by Max. Pass it to the backend and check it there with [verify], together with the user id
 * from [WebAppData.validate]:
 *
 * ```kotlin
 * val data = WebAppData.validate(initData, botToken)
 * val phone = WebAppPhone(body.phone, body.authDate, body.hash)
 * if (!phone.verify(data.user!!.userId, botToken)) throw Unauthorized()
 * ```
 *
 * The signature is `hex(HMAC_SHA256(botToken, "authDate=<authDate>\nphone=<phone without +>\nuserId=<userId>"))`:
 * keyed with the bot token itself, unlike `initData`.
 *
 * @property phone the phone number, with or without a leading `+`.
 * @property authDate `authDate` exactly as `requestContact()` returned it, Unix time in seconds.
 * @property hash the signature.
 */
public data class WebAppPhone(
    val phone: String,
    val authDate: String,
    val hash: String,
) {
    /**
     * `true` if Max signed this phone number for the user [userId] of the mini app of the bot [botToken]
     * no more than [maxAge] before [now]. Without the age check, a phone number and signature intercepted
     * once could be presented forever.
     *
     * @param maxAge how old [authDate] may be, one hour by default as for `initData`; `null` does not check the age.
     * @param now the current time; tests pass a fixed one.
     * @throws IllegalArgumentException if [botToken] is blank or [maxAge] is negative.
     */
    @JvmOverloads
    public fun verify(
        userId: Long,
        botToken: String,
        maxAge: Duration? = WebAppData.DEFAULT_MAX_AGE,
        now: Instant = Instant.now(),
    ): Boolean = verify(phone, authDate, userId, hash, botToken, maxAge, now)

    public companion object {
        /** Same as [WebAppPhone.verify], without creating a [WebAppPhone]. */
        @JvmStatic
        @JvmOverloads
        public fun verify(
            phone: String,
            authDate: String,
            userId: Long,
            hash: String,
            botToken: String,
            maxAge: Duration? = WebAppData.DEFAULT_MAX_AGE,
            now: Instant = Instant.now(),
        ): Boolean {
            Hmac.requireToken(botToken)
            require(maxAge == null || !maxAge.isNegative) { "maxAge must not be negative: $maxAge" }
            val message = "authDate=$authDate\nphone=${phone.removePrefix("+")}\nuserId=$userId"
            if (!Hmac.matches(Hmac.hex(botToken.toByteArray(), message), hash)) return false
            if (maxAge == null) return true
            val issued = authDate.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return false
            return Duration.between(issued, now) <= maxAge
        }
    }
}
