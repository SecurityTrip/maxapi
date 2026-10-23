package ru.securitytrip.maxapi.model.chat

import ru.securitytrip.maxapi.model.message.Message

/** Request body of `PUT /chats/{chatId}/pin`. */
public data class PinMessageBody(
    val messageId: String,
    /** Whether members are notified with a system message. Defaults to `true` on the server. */
    val notify: Boolean? = null,
)

/** Response of `GET /chats/{chatId}/pin`. */
public data class GetPinnedMessageResult(
    /** The pinned message, or `null` if nothing is pinned. */
    val message: Message? = null,
)
