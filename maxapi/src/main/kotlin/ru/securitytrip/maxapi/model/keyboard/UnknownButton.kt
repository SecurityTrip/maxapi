package ru.securitytrip.maxapi.model.keyboard

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A button of a type this version of the library does not know.
 * All fields except `type` and `text` are available in [properties].
 */
public class UnknownButton @JsonCreator constructor(
    @JsonProperty("type") override val type: String = "",
    @JsonProperty("text") override val text: String = "",
) : Button() {
    private val extra = LinkedHashMap<String, Any?>()

    /** All fields except `type` and `text`, as parsed by Jackson. */
    @get:JsonIgnore
    public val properties: Map<String, Any?> get() = extra

    @JsonAnySetter
    private fun put(name: String, value: Any?) {
        extra[name] = value
    }

    @JsonAnyGetter
    private fun any(): Map<String, Any?> = extra

    override fun equals(other: Any?): Boolean =
        other is UnknownButton && other.type == type && other.text == text && other.extra == extra

    override fun hashCode(): Int = (type.hashCode() * 31 + text.hashCode()) * 31 + extra.hashCode()

    override fun toString(): String = "UnknownButton(type=$type, text=$text, properties=$extra)"
}
