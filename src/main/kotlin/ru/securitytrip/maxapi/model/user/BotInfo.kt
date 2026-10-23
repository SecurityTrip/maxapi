package ru.securitytrip.maxapi.model.user

/** The current bot, as returned by `MaxClient.getMyInfo`. */
public data class BotInfo(
    override val userId: Long,
    override val firstName: String = "",
    override val lastName: String? = null,
    override val username: String? = null,
    override val isBot: Boolean = true,
    override val lastActivityTime: Long? = null,
    /** Bot description. */
    val description: String? = null,
    /** URL of the avatar. */
    val avatarUrl: String? = null,
    /** URL of a bigger version of the avatar. */
    val fullAvatarUrl: String? = null,
    /** Commands shown to users in the bot menu, up to 32. */
    val commands: List<BotCommand>? = null,
) : UserInfo
