package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.user.User

/** A comment was edited. Requires the `read_all_messages` permission in the channel. */
public data class CommentEditedUpdate(
    override val timestamp: Long,
    val message: Message,
) : Update() {
    override val updateType: String get() = "comment_edited"
    @get:JsonIgnore
    override val chatId: Long? get() = message.recipient.chatId
    @get:JsonIgnore
    override val user: User? get() = message.sender
}
