package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/** A user joined a chat where the bot is an administrator with the `read_all_messages` permission. */
public data class UserAddedToChatUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    /** The user who joined. */
    override val user: User,
    /** User who added them; `null` if they joined by link. */
    val inviterId: Long? = null,
    val isChannel: Boolean = false,
) : Update() {
    override val updateType: String get() = "user_added"
}
