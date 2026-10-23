package ru.securitytrip.maxapi.model.message

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonValue
import ru.securitytrip.maxapi.model.ApiEnum

/** How one message refers to another. */
public enum class MessageLinkType(@get:JsonValue override val value: String) : ApiEnum {
    /** The message forwards the linked one. */
    FORWARD("forward"),

    /** The message replies to the linked one. */
    REPLY("reply"),

    /** A link type this version of the library does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}
