package ru.securitytrip.maxapi.webapp

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
     * `true` if Max signed this phone number for the user [userId] of the mini app of the bot [botToken].
     * It does not check how old [authDate] is.
     * @throws IllegalArgumentException if [botToken] is blank.
     */
    public fun verify(userId: Long, botToken: String): Boolean = verify(phone, authDate, userId, hash, botToken)

    public companion object {
        /** Same as [WebAppPhone.verify], without creating a [WebAppPhone]. */
        @JvmStatic
        public fun verify(phone: String, authDate: String, userId: Long, hash: String, botToken: String): Boolean {
            Hmac.requireToken(botToken)
            val message = "authDate=$authDate\nphone=${phone.removePrefix("+")}\nuserId=$userId"
            return Hmac.matches(Hmac.hex(botToken.toByteArray(), message), hash)
        }
    }
}
