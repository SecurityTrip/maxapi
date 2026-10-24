package ru.securitytrip.maxapi.model.chat

/**
 * An administrator to appoint with `MaxClient.postAdmins`.
 *
 * @property alias title shown next to the administrator's name; `null` by default.
 */
public data class ChatAdmin @JvmOverloads constructor(
    val userId: Long,
    val permissions: Set<ChatAdminPermission>,
    val alias: String? = null,
)

/** Request body of `POST /chats/{chatId}/members/admins`. */
public data class ChatAdminsList(
    val admins: List<ChatAdmin>,
)
