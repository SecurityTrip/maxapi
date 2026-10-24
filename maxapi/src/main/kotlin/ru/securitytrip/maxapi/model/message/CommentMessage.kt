package ru.securitytrip.maxapi.model.message

import ru.securitytrip.maxapi.model.markup.MarkupElement
import ru.securitytrip.maxapi.model.user.User

/**
 * A comment to a channel post. Unlike [Message], it has no public URL and no attachments.
 */
public data class CommentMessage(
    /** Author of the comment; `null` if it was posted on behalf of the channel. */
    val sender: User? = null,
    /** For comments the recipient is always a channel. */
    val recipient: Recipient,
    /** When the comment was created, Unix time in milliseconds. */
    val timestamp: Long,
    /** Forwarded comment or the comment this one replies to. */
    val link: CommentLinkedMessage? = null,
    val body: CommentMessageBody,
    val stat: MessageStat? = null,
)

/** Content of a comment: text only. */
public data class CommentMessageBody(
    /** Unique comment identifier. */
    val mid: String,
    val seq: Long = 0,
    val text: String? = null,
    val markup: List<MarkupElement>? = null,
)

/** A forwarded comment or the comment being replied to. */
public data class CommentLinkedMessage(
    val type: MessageLinkType,
    val sender: User? = null,
    val chatId: Long? = null,
    val message: CommentMessageBody,
)

/** Response of `GET /messages/{messageId}/comments`. */
public data class CommentMessageList(
    val messages: List<CommentMessage> = emptyList(),
)
