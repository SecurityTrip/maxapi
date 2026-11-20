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
    /** Error code when provided by Max. */
    val code: String? = null,
) {
    public constructor(success: Boolean, message: String? = null) : this(success, message, null)

    public fun copy(success: Boolean = this.success, message: String? = this.message): SimpleQueryResult =
        copy(success = success, message = message, code = code)
}
