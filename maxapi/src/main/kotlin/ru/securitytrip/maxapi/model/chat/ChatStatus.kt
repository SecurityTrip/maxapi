package ru.securitytrip.maxapi.model.chat

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import com.fasterxml.jackson.annotation.JsonValue
import ru.securitytrip.maxapi.model.ApiEnum

/** Status of the current bot in a chat. */
public enum class ChatStatus(@get:JsonValue override val value: String) : ApiEnum {
    /** The bot is an active member of the chat. */
    ACTIVE("active"),

    /** The bot was removed from the chat. */
    REMOVED("removed"),

    /** The bot left the chat on its own. */
    LEFT("left"),

    /** The chat was closed. */
    CLOSED("closed"),

    /** The user stopped the bot. Only for dialogs. */
    SUSPENDED("suspended"),

    /** A status this version of the library does not know. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown"),
}
