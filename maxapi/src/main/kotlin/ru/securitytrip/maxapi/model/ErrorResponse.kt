package ru.securitytrip.maxapi.model

/** Body of an error response. The library turns it into a `MaxApiException`. */
public data class ErrorResponse(
    val error: String? = null,
    /** Error key, e.g. `chat.denied`. */
    val code: String? = null,
    /** Human-readable description. */
    val message: String? = null,
)
