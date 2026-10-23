package ru.securitytrip.maxapi.model.update

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import ru.securitytrip.maxapi.model.user.User

/**
 * An event that happened in a chat the bot participates in. Delivered by long polling
 * (`MaxClient.getUpdates`) or to a webhook. The concrete subclass is chosen by `update_type`:
 *
 * | `update_type`                   | Class                                |
 * |---------------------------------|--------------------------------------|
 * | `message_created`               | [MessageCreatedUpdate]               |
 * | `message_callback`              | [MessageCallbackUpdate]              |
 * | `message_edited`                | [MessageEditedUpdate]                |
 * | `message_removed`               | [MessageRemovedUpdate]               |
 * | `comment_created`               | [CommentCreatedUpdate]               |
 * | `comment_edited`                | [CommentEditedUpdate]                |
 * | `comment_removed`               | [CommentRemovedUpdate]               |
 * | `bot_added`                     | [BotAddedToChatUpdate]               |
 * | `bot_removed`                   | [BotRemovedFromChatUpdate]           |
 * | `user_added`                    | [UserAddedToChatUpdate]              |
 * | `user_removed`                  | [UserRemovedFromChatUpdate]          |
 * | `bot_started`                   | [BotStartedUpdate]                   |
 * | `bot_stopped`                   | [BotStoppedUpdate]                   |
 * | `dialog_cleared`                | [DialogClearedUpdate]                |
 * | `dialog_removed`                | [DialogRemovedUpdate]                |
 * | `dialog_muted`                  | [DialogMutedUpdate]                  |
 * | `dialog_unmuted`                | [DialogUnmutedUpdate]                |
 * | `chat_title_changed`            | [ChatTitleChangedUpdate]             |
 * | `bot_admin_permissions_changed` | [BotAdminPermissionsChangedUpdate]   |
 *
 * Types the library does not know yet become [UnknownUpdate], so new server features never
 * break the bot. [chatId] and [user] give uniform access to the chat and the user of any update.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "update_type",
    visible = true,
    defaultImpl = UnknownUpdate::class,
)
@JsonSubTypes(
    JsonSubTypes.Type(MessageCreatedUpdate::class, name = "message_created"),
    JsonSubTypes.Type(MessageCallbackUpdate::class, name = "message_callback"),
    JsonSubTypes.Type(MessageEditedUpdate::class, name = "message_edited"),
    JsonSubTypes.Type(MessageRemovedUpdate::class, name = "message_removed"),
    JsonSubTypes.Type(CommentCreatedUpdate::class, name = "comment_created"),
    JsonSubTypes.Type(CommentEditedUpdate::class, name = "comment_edited"),
    JsonSubTypes.Type(CommentRemovedUpdate::class, name = "comment_removed"),
    JsonSubTypes.Type(BotAddedToChatUpdate::class, name = "bot_added"),
    JsonSubTypes.Type(BotRemovedFromChatUpdate::class, name = "bot_removed"),
    JsonSubTypes.Type(UserAddedToChatUpdate::class, name = "user_added"),
    JsonSubTypes.Type(UserRemovedFromChatUpdate::class, name = "user_removed"),
    JsonSubTypes.Type(BotStartedUpdate::class, name = "bot_started"),
    JsonSubTypes.Type(BotStoppedUpdate::class, name = "bot_stopped"),
    JsonSubTypes.Type(DialogClearedUpdate::class, name = "dialog_cleared"),
    JsonSubTypes.Type(DialogRemovedUpdate::class, name = "dialog_removed"),
    JsonSubTypes.Type(DialogMutedUpdate::class, name = "dialog_muted"),
    JsonSubTypes.Type(DialogUnmutedUpdate::class, name = "dialog_unmuted"),
    JsonSubTypes.Type(ChatTitleChangedUpdate::class, name = "chat_title_changed"),
    JsonSubTypes.Type(BotAdminPermissionsChangedUpdate::class, name = "bot_admin_permissions_changed"),
)
public sealed class Update {
    public abstract val updateType: String

    /** When the event happened, Unix time in milliseconds. */
    public abstract val timestamp: Long

    /**
     * Chat where the event happened, or `null` if it cannot be determined
     * (e.g. a callback whose message was deleted). Not serialized when it is derived from other fields.
     */
    public abstract val chatId: Long?

    /**
     * User who caused the event: the message author, the user who pressed a button,
     * the user who was added to a chat, and so on. `null` if unknown.
     */
    public abstract val user: User?
}

/** The [UpdateType] of an update class; `null` for the base [Update] class and [UnknownUpdate]. */
internal fun updateTypeOf(type: Class<out Update>): UpdateType? = when (type) {
    MessageCreatedUpdate::class.java -> UpdateType.MESSAGE_CREATED
    MessageCallbackUpdate::class.java -> UpdateType.MESSAGE_CALLBACK
    MessageEditedUpdate::class.java -> UpdateType.MESSAGE_EDITED
    MessageRemovedUpdate::class.java -> UpdateType.MESSAGE_REMOVED
    CommentCreatedUpdate::class.java -> UpdateType.COMMENT_CREATED
    CommentEditedUpdate::class.java -> UpdateType.COMMENT_EDITED
    CommentRemovedUpdate::class.java -> UpdateType.COMMENT_REMOVED
    BotAddedToChatUpdate::class.java -> UpdateType.BOT_ADDED
    BotRemovedFromChatUpdate::class.java -> UpdateType.BOT_REMOVED
    UserAddedToChatUpdate::class.java -> UpdateType.USER_ADDED
    UserRemovedFromChatUpdate::class.java -> UpdateType.USER_REMOVED
    BotStartedUpdate::class.java -> UpdateType.BOT_STARTED
    BotStoppedUpdate::class.java -> UpdateType.BOT_STOPPED
    DialogClearedUpdate::class.java -> UpdateType.DIALOG_CLEARED
    DialogRemovedUpdate::class.java -> UpdateType.DIALOG_REMOVED
    DialogMutedUpdate::class.java -> UpdateType.DIALOG_MUTED
    DialogUnmutedUpdate::class.java -> UpdateType.DIALOG_UNMUTED
    ChatTitleChangedUpdate::class.java -> UpdateType.CHAT_TITLE_CHANGED
    BotAdminPermissionsChangedUpdate::class.java -> UpdateType.BOT_ADMIN_PERMISSIONS_CHANGED
    else -> null
}
