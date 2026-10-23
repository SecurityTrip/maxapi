package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/** A user deleted the dialog with the bot. */
public data class DialogRemovedUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    override val user: User,
    val userLocale: String? = null,
) : Update() {
    override val updateType: String get() = "dialog_removed"
}
