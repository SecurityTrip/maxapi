package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/**
 * A user left or was removed from a chat where the bot is an administrator with the
 * `read_all_messages` permission.
 */
public data class UserRemovedFromChatUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    /** The user who left or was removed. */
    override val user: User,
    /** Administrator who removed them; `null` if they left on their own. */
    val adminId: Long? = null,
    val isChannel: Boolean = false,
) : Update() {
    override val updateType: String get() = "user_removed"
}
