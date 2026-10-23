package ru.securitytrip.maxapi.model.chat

import ru.securitytrip.maxapi.model.user.UserInfo

/** A member of a chat or channel together with their membership details. */
public data class ChatMember(
    override val userId: Long,
    override val firstName: String = "",
    override val lastName: String? = null,
    override val username: String? = null,
    override val isBot: Boolean = false,
    override val lastActivityTime: Long? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val fullAvatarUrl: String? = null,
    /**
     * Last activity in this chat, Unix time in milliseconds.
     * For large chats and channels it may be outdated and equal to `joinTime`.
     */
    val lastAccessTime: Long = 0,
    /** `true` if the member owns the chat. */
    val isOwner: Boolean = false,
    /** `true` if the member is an administrator. */
    val isAdmin: Boolean = false,
    /** When the member joined, Unix time in milliseconds. */
    val joinTime: Long = 0,
    /** Administrator permissions; `null` if the member is not an administrator. */
    val permissions: Set<ChatAdminPermission>? = null,
    /** Administrator title shown in the chat; `null` by default. */
    val alias: String? = null,
) : UserInfo

/** A page of chat members. */
public data class ChatMembersList(
    val members: List<ChatMember> = emptyList(),
    /** Pass it to the next `getMembers` call to get the next page; `null` on the last page. */
    val marker: Long? = null,
)
