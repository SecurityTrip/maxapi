package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonValue
import ru.securitytrip.maxapi.model.ApiEnum

/**
 * Types of [Update]s. Used to filter `getUpdates` and webhook subscriptions.
 * Each constant corresponds to an [Update] subclass with the same name.
 */
public enum class UpdateType(@get:JsonValue override val value: String) : ApiEnum {
    MESSAGE_CREATED("message_created"),
    MESSAGE_CALLBACK("message_callback"),
    MESSAGE_EDITED("message_edited"),
    MESSAGE_REMOVED("message_removed"),
    COMMENT_CREATED("comment_created"),
    COMMENT_EDITED("comment_edited"),
    COMMENT_REMOVED("comment_removed"),
    BOT_ADDED("bot_added"),
    BOT_REMOVED("bot_removed"),
    USER_ADDED("user_added"),
    USER_REMOVED("user_removed"),
    BOT_STARTED("bot_started"),
    BOT_STOPPED("bot_stopped"),
    DIALOG_CLEARED("dialog_cleared"),
    DIALOG_REMOVED("dialog_removed"),
    DIALOG_MUTED("dialog_muted"),
    DIALOG_UNMUTED("dialog_unmuted"),
    CHAT_TITLE_CHANGED("chat_title_changed"),
    BOT_ADMIN_PERMISSIONS_CHANGED("bot_admin_permissions_changed"),
}
