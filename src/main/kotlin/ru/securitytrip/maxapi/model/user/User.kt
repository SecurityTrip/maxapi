package ru.securitytrip.maxapi.model.user

/** A user, as embedded in messages and updates. */
public data class User(
    override val userId: Long,
    override val firstName: String = "",
    override val lastName: String? = null,
    override val username: String? = null,
    override val isBot: Boolean = false,
    override val lastActivityTime: Long? = null,
) : UserInfo
