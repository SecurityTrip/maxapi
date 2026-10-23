package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.user.User

/** A message was edited. In group chats requires the `read_all_messages` permission. */
public data class MessageEditedUpdate(
    override val timestamp: Long,
    val message: Message,
) : Update() {
    override val updateType: String get() = "message_edited"
    @get:JsonIgnore
    override val chatId: Long? get() = message.recipient.chatId
    @get:JsonIgnore
    override val user: User? get() = message.sender
}
