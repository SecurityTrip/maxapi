package ru.securitytrip.maxapi.model

/**
 * Generic result of operations that return no data.
 *
 * Note that a request can complete with HTTP 200 and still have `success = false`;
 * check `success` when it matters.
 */
public data class SimpleQueryResult(
    /** `true` if the operation succeeded. */
    val success: Boolean,
    /** Explanation when the operation did not succeed. */
    val message: String? = null,
)
