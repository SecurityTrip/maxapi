package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/**
 * A user pressed "Start" in the dialog with the bot, or opened a deep link
 * `https://max.ru/<botName>?start=<payload>`.
 */
public data class BotStartedUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    override val user: User,
    /** The `start` parameter of the deep link. The server drops values longer than 128 characters. */
    val payload: String? = null,
    val userLocale: String? = null,
) : Update() {
    override val updateType: String get() = "bot_started"
}
