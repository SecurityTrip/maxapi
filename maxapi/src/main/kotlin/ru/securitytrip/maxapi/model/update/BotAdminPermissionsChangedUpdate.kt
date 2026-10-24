package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonIgnore
import ru.securitytrip.maxapi.model.chat.ChatAdminPermission
import ru.securitytrip.maxapi.model.user.User

/** Administrator permissions of the bot changed in a chat or channel. */
public data class BotAdminPermissionsChangedUpdate(
    override val timestamp: Long,
    override val chatId: Long,
    /** User or bot who changed the permissions. */
    val userId: Long,
    val botId: Long,
    val isChannel: Boolean = false,
    val isAdmin: Boolean = false,
    val permissions: Set<ChatAdminPermission>? = null,
) : Update() {
    override val updateType: String get() = "bot_admin_permissions_changed"
    @get:JsonIgnore
    override val user: User? get() = null
}
