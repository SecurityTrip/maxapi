package ru.securitytrip.maxapi.model.user

import ru.securitytrip.maxapi.model.chat.ChatMember

/**
 * Fields shared by every representation of a user: [User], [UserWithPhoto], [BotInfo] and [ChatMember].
 */
public interface UserInfo {
    /** Unique user identifier. */
    public val userId: Long

    /** First name. Empty if the server did not send it. */
    public val firstName: String

    /** Last name, if set. */
    public val lastName: String?

    /** Public unique name without `@`. `null` if the user is not accessible or did not set one. */
    public val username: String?

    /** `true` if the user is a bot. */
    public val isBot: Boolean

    /**
     * Time of the last activity in Max, Unix time in milliseconds.
     * Can be outdated if the user hid their online status.
     */
    public val lastActivityTime: Long?
}
