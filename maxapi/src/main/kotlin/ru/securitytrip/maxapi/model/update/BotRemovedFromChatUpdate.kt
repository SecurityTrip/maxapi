package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/** The bot was removed from a chat or channel. */
public data class BotRemovedFromChatUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    /** User who removed the bot. */
    override val user: User,
    val isChannel: Boolean = false,
) : Update() {
    override val updateType: String get() = "bot_removed"
}
