package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import ru.securitytrip.maxapi.model.user.User

/**
 * An update of a type this version of the library does not know.
 * All fields except `update_type` and `timestamp` are available in [properties].
 */
public class UnknownUpdate @JsonCreator constructor(
    @JsonProperty("update_type") override val updateType: String = "",
    @JsonProperty("timestamp") override val timestamp: Long = 0,
) : Update() {
    private val extra = LinkedHashMap<String, Any?>()

    @get:JsonIgnore
    public val properties: Map<String, Any?> get() = extra

    @get:JsonIgnore
    override val chatId: Long? get() = (extra["chat_id"] as? Number)?.toLong()

    @get:JsonIgnore
    override val user: User? get() = null

    @JsonAnySetter
    private fun put(name: String, value: Any?) {
        extra[name] = value
    }

    @JsonAnyGetter
    private fun any(): Map<String, Any?> = extra

    override fun equals(other: Any?): Boolean =
        other is UnknownUpdate && other.updateType == updateType && other.timestamp == timestamp &&
            other.extra == extra

    override fun hashCode(): Int = (updateType.hashCode() * 31 + timestamp.hashCode()) * 31 + extra.hashCode()

    override fun toString(): String = "UnknownUpdate(updateType=$updateType, timestamp=$timestamp, properties=$extra)"
}
