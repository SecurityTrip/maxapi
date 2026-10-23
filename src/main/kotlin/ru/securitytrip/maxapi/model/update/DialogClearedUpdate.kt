package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/** A user cleared the history of the dialog with the bot. */
public data class DialogClearedUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    override val user: User,
    val userLocale: String? = null,
) : Update() {
    override val updateType: String get() = "dialog_cleared"
}
