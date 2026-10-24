package ru.securitytrip.maxapi.model.chat

import com.fasterxml.jackson.annotation.JsonValue
import ru.securitytrip.maxapi.model.ApiEnum

/** Bot activity shown to chat members, see `MaxClient.sendAction`. */
public enum class SenderAction(@get:JsonValue override val value: String) : ApiEnum {
    /** "Bot is typing…" */
    TYPING_ON("typing_on"),
    SENDING_PHOTO("sending_photo"),
    SENDING_VIDEO("sending_video"),
    SENDING_AUDIO("sending_audio"),
    SENDING_FILE("sending_file"),

    /** Marks messages in the chat as read. */
    MARK_SEEN("mark_seen"),
}

/** Request body of `POST /chats/{chatId}/actions`. */
public data class ActionRequestBody(
    val action: SenderAction,
)
