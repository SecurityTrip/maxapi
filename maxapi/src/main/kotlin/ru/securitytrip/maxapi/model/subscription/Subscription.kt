package ru.securitytrip.maxapi.model.subscription


/** An active webhook subscription. */
public data class Subscription(
    /** Webhook URL. */
    val url: String,
    /** When the subscription was created, Unix time in milliseconds. */
    val time: Long = 0,
    /** Update types the bot is subscribed to; `null` means all. */
    val updateTypes: Set<String>? = null,
)

/** Response of `GET /subscriptions`. */
public data class GetSubscriptionsResult(
    val subscriptions: List<Subscription> = emptyList(),
)
