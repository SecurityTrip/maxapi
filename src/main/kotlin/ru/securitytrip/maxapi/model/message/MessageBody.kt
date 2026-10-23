package ru.securitytrip.maxapi.model.message

import ru.securitytrip.maxapi.model.attachment.Attachment
import ru.securitytrip.maxapi.model.markup.MarkupElement

/** Content of a message. */
public data class MessageBody(
    /** Unique message identifier. Use it to edit, delete, reply to or pin the message. */
    val mid: String,
    /** Sequence number of the message in the chat. */
    val seq: Long = 0,
    val text: String? = null,
    /** Attachments; check the concrete type with `is PhotoAttachment` and so on. */
    val attachments: List<Attachment>? = null,
    /** Formatting of `text`. */
    val markup: List<MarkupElement>? = null,
)
