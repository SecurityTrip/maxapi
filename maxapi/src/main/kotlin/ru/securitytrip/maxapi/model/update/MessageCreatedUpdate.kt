package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.securitytrip.maxapi.model.chat.ChatAdminPermission
import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.user.User

/**
 * A new message. In group chats the bot receives it only if it is an administrator with the
 * [ChatAdminPermission.READ_ALL_MESSAGES] permission.
 */
public data class MessageCreatedUpdate(
    override val timestamp: Long,
    val message: Message,
    /** User locale in IETF BCP 47 format, e.g. `ru-RU`. Only in dialogs. */
    val userLocale: String? = null,
) : Update() {
    override val updateType: String get() = "message_created"
    @get:JsonIgnore
    override val chatId: Long? get() = message.recipient.chatId
    @get:JsonIgnore
    override val user: User? get() = message.sender
}
