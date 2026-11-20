package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/** The bot was added to a chat or channel. */
public data class BotAddedToChatUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    /** User who added the bot, when present in an older event payload. */
    override val user: User? = null,
    val isChannel: Boolean = false,
) : Update() {
    override val updateType: String get() = "bot_added"
}
