package ru.securitytrip.maxapi.model.update

import ru.securitytrip.maxapi.model.user.User

/** The title of a chat was changed. */
public data class ChatTitleChangedUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    override val user: User,
    val title: String,
) : Update() {
    override val updateType: String get() = "chat_title_changed"
}
