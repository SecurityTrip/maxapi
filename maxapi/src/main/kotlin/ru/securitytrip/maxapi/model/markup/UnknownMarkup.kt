package ru.securitytrip.maxapi.model.markup

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A markup element of a type this version of the library does not know.
 * All fields except `type`, `from` and `length` are available in [properties].
 */
public class UnknownMarkup @JsonCreator constructor(
    @JsonProperty("type") override val type: String = "",
    @JsonProperty("from") override val from: Int = 0,
    @JsonProperty("length") override val length: Int = 0,
) : MarkupElement() {
    private val extra = LinkedHashMap<String, Any?>()

    /** All fields except `type`, `from` and `length`, as parsed by Jackson. */
    @get:JsonIgnore
    public val properties: Map<String, Any?> get() = extra

    @JsonAnySetter
    private fun put(name: String, value: Any?) {
        extra[name] = value
    }

    @JsonAnyGetter
    private fun any(): Map<String, Any?> = extra

    override fun equals(other: Any?): Boolean =
        other is UnknownMarkup && other.type == type && other.from == from &&
            other.length == length && other.extra == extra

    override fun hashCode(): Int = ((type.hashCode() * 31 + from) * 31 + length) * 31 + extra.hashCode()

    override fun toString(): String = "UnknownMarkup(type=$type, from=$from, length=$length, properties=$extra)"
}
