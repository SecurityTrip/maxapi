package ru.securitytrip.maxapi.model.attachment

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * An attachment of a type this version of the library does not know.
 * All fields of the JSON object except `type` are available in [properties].
 */
public class UnknownAttachment @JsonCreator constructor(
    @JsonProperty("type") override val type: String = "",
) : Attachment() {
    private val extra = LinkedHashMap<String, Any?>()

    /** All fields except `type`, as parsed by Jackson (maps, lists, strings, numbers, booleans). */
    @get:JsonIgnore
    public val properties: Map<String, Any?> get() = extra

    @JsonAnySetter
    private fun put(name: String, value: Any?) {
        extra[name] = value
    }

    @JsonAnyGetter
    private fun any(): Map<String, Any?> = extra

    override fun equals(other: Any?): Boolean =
        other is UnknownAttachment && other.type == type && other.extra == extra

    override fun hashCode(): Int = type.hashCode() * 31 + extra.hashCode()

    override fun toString(): String = "UnknownAttachment(type=$type, properties=$extra)"
}
