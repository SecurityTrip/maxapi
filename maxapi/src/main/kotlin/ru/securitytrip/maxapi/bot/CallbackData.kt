package ru.securitytrip.maxapi.bot

/**
 * A typed format for callback button payloads: a prefix and named fields, packed as
 * `prefix:value1:value2`.
 *
 * ```kotlin
 * val order = CallbackData("order", "id", "action")
 *
 * keyboard { row { callback("Buy", order.pack(42, "buy")) } }   // payload "order:42:buy"
 *
 * bot.onCallback(order) {
 *     val fields = data(order)            // {id=42, action=buy}
 *     answer("Order ${fields["id"]}: ${fields["action"]}")
 * }
 * ```
 *
 * Values must not contain `:`, except the value of the last field. A packed payload may be at most
 * [MAX_LENGTH] characters, the limit of the API.
 *
 * @property prefix identifies the kind of button; must not be empty or contain `:`.
 */
public class CallbackData(public val prefix: String, vararg fields: String) {
    /** Names of the fields, in packing order. */
    public val fields: List<String> = fields.toList()

    init {
        require(prefix.isNotEmpty() && SEPARATOR !in prefix) { "prefix must not be empty or contain '$SEPARATOR'" }
        require(this.fields.toSet().size == this.fields.size) { "field names must be unique" }
    }

    /**
     * Packs [values] in the order of [fields]; `null` becomes an empty string.
     * @throws IllegalArgumentException if the number of values differs from the number of fields,
     *   a value other than the last contains `:`, or the result is longer than [MAX_LENGTH].
     */
    public fun pack(vararg values: Any?): String {
        require(values.size == fields.size) { "Expected ${fields.size} values for $fields, got ${values.size}" }
        val parts = values.map { it?.toString().orEmpty() }
        parts.dropLast(1).forEachIndexed { i, value ->
            require(SEPARATOR !in value) { "Value of '${fields[i]}' must not contain '$SEPARATOR': $value" }
        }
        val payload = (listOf(prefix) + parts).joinToString(SEPARATOR.toString())
        require(payload.length <= MAX_LENGTH) { "Payload is longer than $MAX_LENGTH characters" }
        return payload
    }

    /** Packs [values] by field name; missing fields become empty strings. */
    public fun pack(values: Map<String, Any?>): String {
        val unknown = values.keys - fields.toSet()
        require(unknown.isEmpty()) { "Unknown fields: $unknown" }
        return pack(*fields.map { values[it] }.toTypedArray())
    }

    /** The fields of [payload] by name, or `null` if it was not packed by this format. */
    public fun unpack(payload: String?): Map<String, String>? {
        if (payload == null) return null
        val parts = payload.split(SEPARATOR, limit = fields.size + 1)
        if (parts.size != fields.size + 1 || parts[0] != prefix) return null
        return fields.zip(parts.drop(1)).toMap()
    }

    /** Whether [payload] was packed by this format. */
    public fun matches(payload: String?): Boolean = unpack(payload) != null

    /** A filter for button presses with payloads of this format. */
    public fun filter(): Filter<CallbackContext> = Filters.of { matches(it.payload) }

    override fun toString(): String = "CallbackData($prefix, $fields)"

    public companion object {
        /** Separator between the prefix and the values. */
        public const val SEPARATOR: Char = ':'

        /** Maximum length of a button payload. */
        public const val MAX_LENGTH: Int = 1024
    }
}
