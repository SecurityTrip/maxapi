package ru.securitytrip.maxapi.model.chat

import ru.securitytrip.maxapi.model.message.Message
import ru.securitytrip.maxapi.model.user.UserWithPhoto

/** A dialog, group chat or channel, as returned by `MaxClient.getChat`. */
public data class Chat(
    /** Chat identifier. Group chats and channels have negative identifiers. */
    val chatId: Long,
    val type: ChatType = ChatType.UNKNOWN,
    /** Status of the current bot in this chat. */
    val status: ChatStatus = ChatStatus.UNKNOWN,
    /** Visible title. Can be `null` for dialogs. */
    val title: String? = null,
    val icon: Image? = null,
    /** Time of the last event in the chat, Unix time in milliseconds. */
    val lastEventTime: Long = 0,
    /** Number of members. Always 2 for dialogs. */
    val participantsCount: Int = 0,
    /** Identifier of the chat owner. Visible only to administrators. */
    val ownerId: Long? = null,
    /**
     * Members and the time of their last activity (user id → Unix time in milliseconds).
     * Visible only to administrators; `null` when chats are listed.
     */
    val participants: Map<String, Long>? = null,
    /** `true` if the chat is publicly available. Always `false` for dialogs. */
    val isPublic: Boolean = false,
    /** Invite link of the chat. */
    val link: String? = null,
    val description: String? = null,
    /** The other participant of a dialog. Only for [ChatType.DIALOG]. */
    val dialogWithUser: UserWithPhoto? = null,
    /** Number of messages. Only for group chats and channels. */
    val messagesCount: Int? = null,
    /** Pinned message. Returned only when a single chat is requested. */
    val pinnedMessage: Message? = null,
)

/** An image referenced by URL, e.g. a chat icon. */
public data class Image(
    val url: String,
)
