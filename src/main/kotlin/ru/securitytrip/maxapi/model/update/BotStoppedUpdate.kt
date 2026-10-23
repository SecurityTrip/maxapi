package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/** A user stopped the bot. Sending messages to them fails until they start it again. */
public data class BotStoppedUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    override val user: User,
    val userLocale: String? = null,
) : Update() {
    override val updateType: String get() = "bot_stopped"
}
