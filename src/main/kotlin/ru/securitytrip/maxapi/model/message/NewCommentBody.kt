package ru.securitytrip.maxapi.model.message

import ru.securitytrip.maxapi.model.markup.TextFormat

/** A comment to send or the new content of a comment being edited. Attachments are not allowed. */
public data class NewCommentBody @JvmOverloads constructor(
    /** Text, up to 4000 characters. */
    val text: String? = null,
    /** Makes the comment a reply to or a forward of another one. */
    val link: NewMessageLink? = null,
    val format: TextFormat? = null,
)

/** Response of `POST /messages/{messageId}/comments`. */
public data class SendCommentResult(
    val message: CommentMessage,
)
