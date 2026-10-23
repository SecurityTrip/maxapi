package ru.securitytrip.maxapi.model.subscription


/** Request body of `POST /subscriptions`. */
public data class SubscriptionRequestBody @JvmOverloads constructor(
    /** HTTPS URL of the webhook. The server must listen on port 443. */
    val url: String,
    /**
     * Sent in the `X-Max-Bot-Api-Secret` header of every webhook request, so the bot can tell
     * that a request comes from Max. 5–256 characters of `[A-Za-z0-9_-]`.
     */
    val secret: String? = null,
    /** Update types to deliver, e.g. `message_created`; `null` means all. */
    val updateTypes: Set<String>? = null,
)
