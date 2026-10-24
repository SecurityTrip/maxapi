package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.user.User

/**
 * A new comment to a channel post. The bot receives it only if it is an administrator of the
 * channel with the `read_all_messages` permission.
 */
public data class CommentCreatedUpdate(
    override val timestamp: Long,
    val message: Message,
) : Update() {
    override val updateType: String get() = "comment_created"
    @get:JsonIgnore
    override val chatId: Long? get() = message.recipient.chatId
    @get:JsonIgnore
    override val user: User? get() = message.sender
}
