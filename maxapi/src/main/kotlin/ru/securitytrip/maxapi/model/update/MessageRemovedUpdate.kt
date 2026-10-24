package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.securitytrip.maxapi.model.user.User

/** A message was deleted. In group chats requires the `read_all_messages` permission. */
public data class MessageRemovedUpdate(
    override val timestamp: Long,
    val messageId: String,
    override val chatId: Long,
    /** User who deleted the message. */
    val userId: Long,
) : Update() {
    override val updateType: String get() = "message_removed"
    @get:JsonIgnore
    override val user: User? get() = null
}
