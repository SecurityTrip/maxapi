package ru.securitytrip.maxapi.model.chat

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonValue
import ru.securitytrip.maxapi.model.ApiEnum

/** Kind of a chat. */
public enum class ChatType(@get:JsonValue override val value: String) : ApiEnum {
    /** One-on-one conversation between the bot and a user. */
    DIALOG("dialog"),

    /** Group chat. */
    CHAT("chat"),

    /** Channel. */
    CHANNEL("channel"),

    /** A type this version of the library does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}
