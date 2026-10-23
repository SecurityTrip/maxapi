package ru.securitytrip.maxapi.model.message

import ru.securitytrip.maxapi.model.user.User

/** A message in a chat. */
public data class Message(
    /** Author of the message; `null` if it was posted on behalf of a channel. */
    val sender: User? = null,
    val recipient: Recipient,
    /** When the message was created, Unix time in milliseconds. */
    val timestamp: Long,
    /** Forwarded message or the message this one replies to. */
    val link: LinkedMessage? = null,
    /** Text and attachments; `null` if the message contains only a forwarded message. */
    val body: MessageBody? = null,
    /** View statistics. Available only for channels in `getMessages`. */
    val stat: MessageStat? = null,
    /** Public URL of the message; `null` for dialogs and private chats. */
    val url: String? = null,
)

/** Message statistics. */
public data class MessageStat(
    val views: Int = 0,
)

/** Response of `GET /messages`. */
public data class MessageList(
    val messages: List<Message> = emptyList(),
)
