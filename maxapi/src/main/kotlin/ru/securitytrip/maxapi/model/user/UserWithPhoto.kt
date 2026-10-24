package ru.securitytrip.maxapi.model.user

import ru.securitytrip.maxapi.model.chat.Chat

/** A user with profile description and avatar, e.g. [Chat.dialogWithUser]. */
public data class UserWithPhoto(
    override val userId: Long,
    override val firstName: String = "",
    override val lastName: String? = null,
    override val username: String? = null,
    override val isBot: Boolean = false,
    override val lastActivityTime: Long? = null,
    /** Profile description, up to 16000 characters. `null` if not filled in. */
    val description: String? = null,
    /** URL of the avatar. */
    val avatarUrl: String? = null,
    /** URL of a bigger version of the avatar. */
    val fullAvatarUrl: String? = null,
) : UserInfo
