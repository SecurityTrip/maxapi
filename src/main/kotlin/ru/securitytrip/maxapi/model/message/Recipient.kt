package ru.securitytrip.maxapi.model.message

import ru.securitytrip.maxapi.model.chat.ChatType

/** Where a message was sent: a user, a chat or a channel. */
public data class Recipient(
    /** Chat identifier. Dialogs also have one, and replying to it reaches the user. */
    val chatId: Long? = null,
    val chatType: ChatType = ChatType.UNKNOWN,
    /** User identifier, if the message was sent to a user. */
    val userId: Long? = null,
    /** Post identifier, for comments. */
    val postId: String? = null,
)
