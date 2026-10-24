package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/** A user muted the dialog with the bot. */
public data class DialogMutedUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    override val user: User,
    /** Unix time in milliseconds until which the dialog is muted. */
    val mutedUntil: Long = 0,
    val userLocale: String? = null,
) : Update() {
    override val updateType: String get() = "dialog_muted"
}
